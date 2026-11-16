package com.gallerysync.app.data.restoration

import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoReconstructionEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var engine: VideoReconstructionEngine

    @Before
    fun setUp() {
        engine = VideoReconstructionEngine()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun singleChunkReconstruction_exactByteEquality() = runBlocking {
        val originalBytes = Random.nextBytes(128 * 1024) // 128 KiB
        val expectedSha = sha256(originalBytes)

        val chunkFile = tempFolder.newFile("chunk_0.bin").apply { writeBytes(originalBytes) }
        val outputFile = File(tempFolder.root, "reconstructed.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(
                index = 0,
                startOffset = 0L,
                endOffsetExclusive = originalBytes.size.toLong(),
                sizeBytes = originalBytes.size.toLong(),
                sha256 = expectedSha,
                telegramFileId = "file_chunk_0",
                telegramMessageId = 100L
            )
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to chunkFile),
            chunkDescriptors = descriptors,
            expectedTotalSize = originalBytes.size.toLong(),
            expectedFullSha256 = expectedSha,
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.Success)
        val success = result as ReconstructionResult.Success
        assertEquals(originalBytes.size.toLong(), success.totalBytes)
        assertEquals(expectedSha, success.fullSha256)
        assertTrue(outputFile.exists())
        assertArrayEquals(originalBytes, outputFile.readBytes())
    }

    @Test
    fun multiChunkReconstruction_uniformChunks_exactByteEquality() = runBlocking {
        val totalSize = 300 * 1024 // 300 KiB
        val originalBytes = Random.nextBytes(totalSize)
        val fullSha = sha256(originalBytes)

        val chunk0Bytes = originalBytes.copyOfRange(0, 150 * 1024)
        val chunk1Bytes = originalBytes.copyOfRange(150 * 1024, totalSize)

        val chunk0File = tempFolder.newFile("chunk_0.bin").apply { writeBytes(chunk0Bytes) }
        val chunk1File = tempFolder.newFile("chunk_1.bin").apply { writeBytes(chunk1Bytes) }
        val outputFile = File(tempFolder.root, "reconstructed_uniform.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(
                index = 0,
                startOffset = 0L,
                endOffsetExclusive = 150 * 1024L,
                sizeBytes = 150 * 1024L,
                sha256 = sha256(chunk0Bytes),
                telegramFileId = "fid_0",
                telegramMessageId = 1L
            ),
            ManifestChunkDescriptor(
                index = 1,
                startOffset = 150 * 1024L,
                endOffsetExclusive = totalSize.toLong(),
                sizeBytes = 150 * 1024L,
                sha256 = sha256(chunk1Bytes),
                telegramFileId = "fid_1",
                telegramMessageId = 2L
            )
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to chunk0File, 1 to chunk1File),
            chunkDescriptors = descriptors,
            expectedTotalSize = totalSize.toLong(),
            expectedFullSha256 = fullSha,
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.Success)
        val success = result as ReconstructionResult.Success
        assertEquals(totalSize.toLong(), success.totalBytes)
        assertEquals(fullSha, success.fullSha256)
        assertArrayEquals(originalBytes, outputFile.readBytes())
    }

    @Test
    fun multiChunkReconstruction_nonUniformFinalChunk() = runBlocking {
        // Chunk 0: 100 KB, Chunk 1: 100 KB, Chunk 2: 45 KB (Total 245 KB)
        val totalSize = 245 * 1024
        val originalBytes = Random.nextBytes(totalSize)
        val fullSha = sha256(originalBytes)

        val c0 = originalBytes.copyOfRange(0, 100 * 1024)
        val c1 = originalBytes.copyOfRange(100 * 1024, 200 * 1024)
        val c2 = originalBytes.copyOfRange(200 * 1024, totalSize)

        val f0 = tempFolder.newFile("c0.bin").apply { writeBytes(c0) }
        val f1 = tempFolder.newFile("c1.bin").apply { writeBytes(c1) }
        val f2 = tempFolder.newFile("c2.bin").apply { writeBytes(c2) }
        val outputFile = File(tempFolder.root, "reconstructed_nonuniform.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 102400L, sizeBytes = 102400L, sha256 = sha256(c0), telegramFileId = "f0"),
            ManifestChunkDescriptor(index = 1, startOffset = 102400L, endOffsetExclusive = 204800L, sizeBytes = 102400L, sha256 = sha256(c1), telegramFileId = "f1"),
            ManifestChunkDescriptor(index = 2, startOffset = 204800L, endOffsetExclusive = 250880L, sizeBytes = 46080L, sha256 = sha256(c2), telegramFileId = "f2")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0, 1 to f1, 2 to f2),
            chunkDescriptors = descriptors,
            expectedTotalSize = totalSize.toLong(),
            expectedFullSha256 = fullSha,
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.Success)
        assertArrayEquals(originalBytes, outputFile.readBytes())
    }

    @Test
    fun reconstruction_failsOnGapBetweenChunks() = runBlocking {
        val f0 = tempFolder.newFile("g0.bin").apply { writeBytes(ByteArray(100)) }
        val f1 = tempFolder.newFile("g1.bin").apply { writeBytes(ByteArray(100)) }
        val outputFile = File(tempFolder.root, "out_gap.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 100L, sizeBytes = 100L, sha256 = "sha0", telegramFileId = "f0"),
            // Gap of 10 bytes: starts at 110 instead of 100
            ManifestChunkDescriptor(index = 1, startOffset = 110L, endOffsetExclusive = 210L, sizeBytes = 100L, sha256 = "sha1", telegramFileId = "f1")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0, 1 to f1),
            chunkDescriptors = descriptors,
            expectedTotalSize = 210L,
            expectedFullSha256 = "dummy_sha",
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.ValidationFailed)
        assertFalse(outputFile.exists())
    }

    @Test
    fun reconstruction_failsOnOverlappingChunks() = runBlocking {
        val f0 = tempFolder.newFile("o0.bin").apply { writeBytes(ByteArray(100)) }
        val f1 = tempFolder.newFile("o1.bin").apply { writeBytes(ByteArray(100)) }
        val outputFile = File(tempFolder.root, "out_overlap.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 100L, sizeBytes = 100L, sha256 = "sha0", telegramFileId = "f0"),
            // Overlap: starts at 80 instead of 100
            ManifestChunkDescriptor(index = 1, startOffset = 80L, endOffsetExclusive = 180L, sizeBytes = 100L, sha256 = "sha1", telegramFileId = "f1")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0, 1 to f1),
            chunkDescriptors = descriptors,
            expectedTotalSize = 180L,
            expectedFullSha256 = "dummy_sha",
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.ValidationFailed)
        assertFalse(outputFile.exists())
    }

    @Test
    fun reconstruction_failsOnMissingChunkFile() = runBlocking {
        val f0 = tempFolder.newFile("m0.bin").apply { writeBytes(ByteArray(100)) }
        val outputFile = File(tempFolder.root, "out_missing.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 100L, sizeBytes = 100L, sha256 = "sha0", telegramFileId = "f0"),
            ManifestChunkDescriptor(index = 1, startOffset = 100L, endOffsetExclusive = 200L, sizeBytes = 100L, sha256 = "sha1", telegramFileId = "f1")
        )

        // Chunk 1 not provided in map
        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0),
            chunkDescriptors = descriptors,
            expectedTotalSize = 200L,
            expectedFullSha256 = "dummy_sha",
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.ChunkMissing)
        assertEquals(1, (result as ReconstructionResult.ChunkMissing).chunkIndex)
        assertFalse(outputFile.exists())
    }

    @Test
    fun reconstruction_failsOnCorruptedChunkFile() = runBlocking {
        // Chunk 0 says size is 100 bytes, but file has only 50 bytes
        val f0 = tempFolder.newFile("corrupt0.bin").apply { writeBytes(ByteArray(50)) }
        val outputFile = File(tempFolder.root, "out_corrupt.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 100L, sizeBytes = 100L, sha256 = "sha0", telegramFileId = "f0")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0),
            chunkDescriptors = descriptors,
            expectedTotalSize = 100L,
            expectedFullSha256 = "dummy_sha",
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.ChunkCorrupted)
        assertEquals(0, (result as ReconstructionResult.ChunkCorrupted).chunkIndex)
        assertFalse(outputFile.exists())
    }

    @Test
    fun reconstruction_failsOnFullSha256Mismatch() = runBlocking {
        val bytes = Random.nextBytes(1000)
        val f0 = tempFolder.newFile("hash_mismatch.bin").apply { writeBytes(bytes) }
        val outputFile = File(tempFolder.root, "out_bad_hash.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 1000L, sizeBytes = 1000L, sha256 = "sha0", telegramFileId = "f0")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0),
            chunkDescriptors = descriptors,
            expectedTotalSize = 1000L,
            expectedFullSha256 = "expected_different_sha256_hash",
            outputFile = outputFile
        )

        assertTrue(result is ReconstructionResult.VerificationFailed)
        assertFalse(outputFile.exists()) // Staging file should be discarded
    }

    @Test
    fun reconstruction_cooperativeCancellation() = runBlocking {
        val bytes = Random.nextBytes(200 * 1024)
        val f0 = tempFolder.newFile("cancel.bin").apply { writeBytes(bytes) }
        val outputFile = File(tempFolder.root, "out_cancel.mp4")

        val descriptors = listOf(
            ManifestChunkDescriptor(index = 0, startOffset = 0L, endOffsetExclusive = 200 * 1024L, sizeBytes = 200 * 1024L, sha256 = "sha0", telegramFileId = "f0")
        )

        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to f0),
            chunkDescriptors = descriptors,
            expectedTotalSize = 200 * 1024L,
            expectedFullSha256 = sha256(bytes),
            outputFile = outputFile,
            isCancelled = { true }
        )

        assertTrue(result is ReconstructionResult.Cancelled)
        assertFalse(outputFile.exists())
        assertFalse(File(outputFile.parentFile, "${outputFile.name}.part").exists())
    }

    @Test
    fun productionChunkSize_16MiB_plusNonUniformFinalChunk_exactReconstruction(): Unit = runBlocking {
        // Chunk 0: Exactly 16 MiB (16,777,216 bytes)
        val c0Size = 16 * 1024 * 1024L
        // Chunk 1: Non-uniform 1 MiB (1,048,576 bytes)
        val c1Size = 1 * 1024 * 1024L
        val totalSize = c0Size + c1Size

        val c0File = tempFolder.newFile("c0_16mib.bin")
        val c1File = tempFolder.newFile("c1_1mib.bin")
        val outputFile = File(tempFolder.root, "out_17mib.mp4")

        val digest0 = MessageDigest.getInstance("SHA-256")
        val digest1 = MessageDigest.getInstance("SHA-256")
        val fullDigest = MessageDigest.getInstance("SHA-256")

        val buf = ByteArray(64 * 1024)
        for (i in buf.indices) buf[i] = (i % 251).toByte()

        // Stream 16 MiB into c0
        FileOutputStream(c0File).use { out ->
            var written = 0L
            while (written < c0Size) {
                val toWrite = minOf(buf.size.toLong(), c0Size - written).toInt()
                out.write(buf, 0, toWrite)
                digest0.update(buf, 0, toWrite)
                fullDigest.update(buf, 0, toWrite)
                written += toWrite
            }
        }

        // Fill different pattern for c1
        for (i in buf.indices) buf[i] = ((i + 7) % 251).toByte()

        // Stream 1 MiB into c1
        FileOutputStream(c1File).use { out ->
            var written = 0L
            while (written < c1Size) {
                val toWrite = minOf(buf.size.toLong(), c1Size - written).toInt()
                out.write(buf, 0, toWrite)
                digest1.update(buf, 0, toWrite)
                fullDigest.update(buf, 0, toWrite)
                written += toWrite
            }
        }

        val sha0 = digest0.digest().joinToString("") { "%02x".format(it) }
        val sha1 = digest1.digest().joinToString("") { "%02x".format(it) }
        val fullSha = fullDigest.digest().joinToString("") { "%02x".format(it) }

        val descriptors = listOf(
            ManifestChunkDescriptor(0, 0L, c0Size, c0Size, sha0, "f0", 101L),
            ManifestChunkDescriptor(1, c0Size, totalSize, c1Size, sha1, "f1", 102L)
        )

        val startReconTime = System.currentTimeMillis()
        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to c0File, 1 to c1File),
            chunkDescriptors = descriptors,
            expectedTotalSize = totalSize,
            expectedFullSha256 = fullSha,
            outputFile = outputFile
        )
        val reconDurationMs = System.currentTimeMillis() - startReconTime

        assertTrue("Expected Success but was: $result", result is ReconstructionResult.Success)
        val success = result as ReconstructionResult.Success
        assertEquals(totalSize, success.totalBytes)
        assertEquals(fullSha, success.fullSha256)
        assertEquals(totalSize, outputFile.length())
        android.util.Log.i("ReconstructionTest", "Reconstructed 17 MiB file in ${reconDurationMs}ms")
    }
}
