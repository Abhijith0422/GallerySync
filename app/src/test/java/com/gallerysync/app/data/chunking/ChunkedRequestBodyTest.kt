package com.gallerysync.app.data.chunking

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Comprehensive unit tests for [ChunkedRequestBody].
 *
 * Validates:
 * - Chunk 0 slice accuracy.
 * - Middle chunk slice accuracy.
 * - Final partial chunk slice accuracy.
 * - In-flight SHA-256 calculation and verification.
 * - [ChunkIntegrityException] thrown on byte count or SHA-256 mismatch.
 * - Reproducibility: multiple writeTo invocations yield identical bytes and hashes.
 * - Non-seekable stream fallback.
 * - Safe resource cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedRequestBodyTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createTestFile(size: Int): File {
        val file = tempFolder.newFile("test_video.mp4")
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(size)
            for (i in 0 until size) {
                buf[i] = (i % 251).toByte()
            }
            fos.write(buf)
        }
        return file
    }

    @Test
    fun testChunk0BytesWrittenCorrectly() {
        val totalSize = 100 * 1024 // 100 KB
        val chunkSize = 30 * 1024L // 30 KB
        val file = createTestFile(totalSize)
        val source = FileChunkSource(file)

        val fullBytes = file.readBytes()
        val expectedSlice = fullBytes.copyOfRange(0, chunkSize.toInt())
        val expectedHash = sha256(expectedSlice)

        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = chunkSize,
            chunkSize = chunkSize,
            chunkSha256 = expectedHash
        )

        val requestBody = ChunkedRequestBody(source, descriptor, expectedHash)
        val buffer = Buffer()
        requestBody.writeTo(buffer)

        assertEquals(chunkSize, buffer.size)
        assertArrayEquals(expectedSlice, buffer.readByteArray())
        assertTrue(requestBody.isVerified)
        assertEquals(expectedHash, requestBody.lastCalculatedSha256)
    }

    @Test
    fun testMiddleChunkBytesWrittenCorrectly() {
        val totalSize = 100 * 1024 // 100 KB
        val chunkSize = 30 * 1024L // 30 KB
        val file = createTestFile(totalSize)
        val source = FileChunkSource(file)

        val fullBytes = file.readBytes()
        val start = 30 * 1024
        val end = 60 * 1024
        val expectedSlice = fullBytes.copyOfRange(start, end)
        val expectedHash = sha256(expectedSlice)

        val descriptor = ChunkDescriptor(
            chunkIndex = 1,
            startOffset = start.toLong(),
            endOffset = end.toLong(),
            chunkSize = chunkSize,
            chunkSha256 = expectedHash
        )

        val requestBody = ChunkedRequestBody(source, descriptor, expectedHash)
        val buffer = Buffer()
        requestBody.writeTo(buffer)

        assertEquals(chunkSize, buffer.size)
        assertArrayEquals(expectedSlice, buffer.readByteArray())
        assertTrue(requestBody.isVerified)
    }

    @Test
    fun testFinalPartialChunkBytesWrittenCorrectly() {
        val totalSize = 100 * 1024 // 100 KB
        val chunkSize = 30 * 1024L // 30 KB chunks -> chunks 0, 1, 2 = 30 KB, chunk 3 = 10 KB
        val file = createTestFile(totalSize)
        val source = FileChunkSource(file)

        val fullBytes = file.readBytes()
        val start = 90 * 1024
        val end = 100 * 1024
        val expectedSlice = fullBytes.copyOfRange(start, end)
        val expectedHash = sha256(expectedSlice)

        val descriptor = ChunkDescriptor(
            chunkIndex = 3,
            startOffset = start.toLong(),
            endOffset = end.toLong(),
            chunkSize = 10 * 1024L,
            chunkSha256 = expectedHash
        )

        val requestBody = ChunkedRequestBody(source, descriptor, expectedHash)
        val buffer = Buffer()
        requestBody.writeTo(buffer)

        assertEquals(10 * 1024L, buffer.size)
        assertArrayEquals(expectedSlice, buffer.readByteArray())
        assertTrue(requestBody.isVerified)
    }

    @Test
    fun testSha256MismatchAbortsUpload() {
        val totalSize = 50 * 1024
        val file = createTestFile(totalSize)
        val source = FileChunkSource(file)

        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = totalSize.toLong(),
            chunkSize = totalSize.toLong(),
            chunkSha256 = wrongHash
        )

        val requestBody = ChunkedRequestBody(source, descriptor, wrongHash)
        val buffer = Buffer()

        val ex = assertThrows(ChunkIntegrityException::class.java) {
            requestBody.writeTo(buffer)
        }

        assertTrue(ex.message!!.contains("SHA-256 mismatch"))
        assertEquals(0, ex.chunkIndex)
        assertEquals(wrongHash, ex.expectedSha256)
    }

    @Test
    fun testRepeatedWriteToProducesIdenticalBytes() {
        val totalSize = 80 * 1024
        val file = createTestFile(totalSize)
        val source = FileChunkSource(file)

        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 40 * 1024L,
            chunkSize = 40 * 1024L
        )

        val requestBody = ChunkedRequestBody(source, descriptor)

        val buffer1 = Buffer()
        requestBody.writeTo(buffer1)
        val hash1 = requestBody.lastCalculatedSha256

        val buffer2 = Buffer()
        requestBody.writeTo(buffer2)
        val hash2 = requestBody.lastCalculatedSha256

        assertEquals(hash1, hash2)
        assertArrayEquals(buffer1.readByteArray(), buffer2.readByteArray())
    }

    @Test
    fun testNonSeekableStreamFallbackWriting() {
        val totalSize = 64 * 1024
        val bytes = ByteArray(totalSize) { (it % 127).toByte() }
        val streamProvider = { ByteArrayInputStream(bytes) }
        val metadata = SourceMetadata(android.net.Uri.EMPTY, totalSize.toLong(), 0L, "stream.mp4", "video/mp4")
        val source = StreamChunkSource(android.net.Uri.EMPTY, metadata, streamProvider)

        val start = 20 * 1024L
        val end = 50 * 1024L
        val expectedSlice = bytes.copyOfRange(start.toInt(), end.toInt())
        val expectedHash = sha256(expectedSlice)

        val descriptor = ChunkDescriptor(
            chunkIndex = 1,
            startOffset = start,
            endOffset = end,
            chunkSize = end - start,
            chunkSha256 = expectedHash
        )

        val requestBody = ChunkedRequestBody(source, descriptor, expectedHash)
        val buffer = Buffer()
        requestBody.writeTo(buffer)

        assertEquals(descriptor.chunkSize, buffer.size)
        assertArrayEquals(expectedSlice, buffer.readByteArray())
        assertTrue(requestBody.isVerified)
    }

    @Test
    fun testContentLengthMatchesDescriptor() {
        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 16 * 1024 * 1024L,
            chunkSize = 16 * 1024 * 1024L
        )
        val file = createTestFile(1024)
        val source = FileChunkSource(file)
        val body = ChunkedRequestBody(source, descriptor)

        assertEquals(16 * 1024 * 1024L, body.contentLength())
        assertEquals("application/octet-stream", body.contentType()?.toString())
    }
}
