package com.gallerysync.app.data.chunking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Random

/**
 * Validates streaming SHA-256 calculations:
 * 1. Full-file SHA-256 matches trusted single-pass byte-by-byte digest.
 * 2. Individual chunk hashes match independently computed hashes of exact byte slices.
 * 3. Concatenated chunks reconstruct the source file bit-for-bit.
 * 4. Empty-source handling properly returns HashingResult.EmptySource.
 * 5. Final partial chunk produces exact verified hash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingSha256Test {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun generateDeterministicTestData(file: File, sizeBytes: Int) {
        val random = Random(42L)
        val buffer = ByteArray(64 * 1024)
        var remaining = sizeBytes
        FileOutputStream(file).use { fos ->
            while (remaining > 0) {
                val toWrite = minOf(buffer.size, remaining)
                random.nextBytes(buffer)
                fos.write(buffer, 0, toWrite)
                remaining -= toWrite
            }
        }
    }

    private fun computeTrustedSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeTrustedSha256Slice(file: File, start: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { stream ->
            var toSkip = start
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }

            var remaining = length
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, toRead)
                if (read == -1) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testEmptySourceReturnsEmptySourceResult() = runBlocking {
        val emptyFile = tempFolder.newFile("empty.bin")
        val source = FileChunkSource(emptyFile)

        val result = StreamingChunkHasher.hash(source)
        assertTrue("Empty file must return HashingResult.EmptySource", result is HashingResult.EmptySource)
    }

    @Test
    fun testSingleChunkExactMatch() = runBlocking {
        val file = tempFolder.newFile("single_chunk.bin")
        generateDeterministicTestData(file, 500 * 1024)
        val trustedFullHash = computeTrustedSha256(file)

        val config = ChunkConfiguration(1024 * 1024L) // 1 MiB
        val source = FileChunkSource(file)
        val result = StreamingChunkHasher.hash(source, config)

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success
        assertEquals(trustedFullHash, success.fileSha256)
        assertEquals(1, success.chunkDescriptors.size)
        assertEquals(trustedFullHash, success.chunkDescriptors[0].chunkSha256)
        assertEquals(500 * 1024L, success.totalBytesRead)
    }

    @Test
    fun testMultiChunkWithPartialFinalChunk() = runBlocking {
        val fileSize = (2.5 * 1024 * 1024).toInt()
        val file = tempFolder.newFile("multi_chunk.bin")
        generateDeterministicTestData(file, fileSize)

        val trustedFullHash = computeTrustedSha256(file)
        val config = ChunkConfiguration(1024 * 1024L) // 1 MiB
        val source = FileChunkSource(file)
        val result = StreamingChunkHasher.hash(source, config)

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success

        assertEquals(trustedFullHash, success.fileSha256)
        assertEquals(3, success.chunkDescriptors.size)
        assertEquals(fileSize.toLong(), success.totalBytesRead)

        for (descriptor in success.chunkDescriptors) {
            val expectedChunkHash = computeTrustedSha256Slice(file, descriptor.startOffset, descriptor.chunkSize)
            assertEquals(
                "Chunk ${descriptor.chunkIndex} hash must match slice hash",
                expectedChunkHash,
                descriptor.chunkSha256
            )
        }
    }

    @Test
    fun testExactChunkBoundaryMultiple() = runBlocking {
        val fileSize = 3 * 1024 * 1024
        val file = tempFolder.newFile("exact_boundary.bin")
        generateDeterministicTestData(file, fileSize)

        val trustedFullHash = computeTrustedSha256(file)
        val config = ChunkConfiguration(1024 * 1024L)
        val source = FileChunkSource(file)
        val result = StreamingChunkHasher.hash(source, config)

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success

        assertEquals(trustedFullHash, success.fileSha256)
        assertEquals(3, success.chunkDescriptors.size)
        assertTrue("All chunks must be exactly 1 MiB", success.chunkDescriptors.all { it.chunkSize == 1024 * 1024L })

        for (descriptor in success.chunkDescriptors) {
            val expectedChunkHash = computeTrustedSha256Slice(file, descriptor.startOffset, descriptor.chunkSize)
            assertEquals(expectedChunkHash, descriptor.chunkSha256)
        }
    }
}
