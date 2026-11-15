package com.gallerysync.app.data.chunking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Validates dual-path ChunkReader operations:
 * - Seekable fast path reading (Path A).
 * - Non-seekable sequential fallback reading (Path B).
 * - Offset boundaries and exact byte counts.
 * - Premature EOF detection.
 * - Temporary file creation and automatic cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkReaderTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun createTestFileWithKnownPattern(size: Int): File {
        val file = tempFolder.newFile("reader_test_${System.nanoTime()}.bin")
        FileOutputStream(file).use { fos ->
            for (i in 0 until size) {
                fos.write((i % 256))
            }
        }
        return file
    }

    @Test
    fun testSeekablePathReadsExactChunk() {
        runBlocking {
            val fileSize = 500 * 1024 // 500 KB
            val file = createTestFileWithKnownPattern(fileSize)
            val source = FileChunkSource(file)

            val descriptor = ChunkDescriptor(
                chunkIndex = 1,
                startOffset = 100 * 1024L,
                endOffset = 200 * 1024L,
                chunkSize = 100 * 1024L
            )

            val out = ByteArrayOutputStream()
            val bytesRead = ChunkReader.readChunk(source, descriptor, out)

            assertEquals(100 * 1024L, bytesRead)
            val data = out.toByteArray()
            assertEquals(100 * 1024, data.size)

            for (i in data.indices) {
                val expectedByte = ((100 * 1024 + i) % 256).toByte()
                assertEquals("Byte at $i should match pattern", expectedByte, data[i])
            }
        }
    }

    @Test
    fun testNonSeekablePathReadsExactChunk() {
        runBlocking {
            val fileSize = 300 * 1024
            val fullData = ByteArray(fileSize) { (it % 256).toByte() }

            val metadata = SourceMetadata(
                uri = android.net.Uri.parse("content://mock/stream"),
                fileSize = fileSize.toLong()
            )
            val source = StreamChunkSource(
                uri = metadata.uri,
                metadata = metadata,
                streamProvider = { ByteArrayInputStream(fullData) }
            )

            assertFalse("StreamChunkSource must be non-seekable", source.isSeekable())

            val descriptor = ChunkDescriptor(
                chunkIndex = 1,
                startOffset = 50 * 1024L,
                endOffset = 150 * 1024L,
                chunkSize = 100 * 1024L
            )

            val out = ByteArrayOutputStream()
            val bytesRead = ChunkReader.readChunk(source, descriptor, out)

            assertEquals(100 * 1024L, bytesRead)
            val data = out.toByteArray()
            assertEquals(100 * 1024, data.size)

            for (i in data.indices) {
                val expectedByte = ((50 * 1024 + i) % 256).toByte()
                assertEquals(expectedByte, data[i])
            }
        }
    }

    @Test
    fun testPrematureEofThrowsExceptionInSeekablePath() {
        val file = createTestFileWithKnownPattern(50 * 1024) // 50 KB file
        val source = FileChunkSource(file)

        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 100 * 1024L,
            chunkSize = 100 * 1024L
        )

        val out = ByteArrayOutputStream()
        assertThrows(PrematureEofException::class.java) {
            runBlocking {
                ChunkReader.readChunk(source, descriptor, out)
            }
        }
    }

    @Test
    fun testPrematureEofThrowsExceptionInNonSeekablePath() {
        val fullData = ByteArray(30 * 1024)
        val metadata = SourceMetadata(
            uri = android.net.Uri.parse("content://mock/short"),
            fileSize = 30 * 1024L
        )
        val source = StreamChunkSource(
            uri = metadata.uri,
            metadata = metadata,
            streamProvider = { ByteArrayInputStream(fullData) }
        )

        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 50 * 1024L,
            chunkSize = 50 * 1024L
        )

        val out = ByteArrayOutputStream()
        assertThrows(PrematureEofException::class.java) {
            runBlocking {
                ChunkReader.readChunk(source, descriptor, out)
            }
        }
    }

    @Test
    fun testCreateAndCleanTempChunkFile() {
        runBlocking {
            val file = createTestFileWithKnownPattern(200 * 1024)
            val source = FileChunkSource(file)
            val cacheDir = tempFolder.newFolder("cache")

            val descriptor = ChunkDescriptor(
                chunkIndex = 0,
                startOffset = 0L,
                endOffset = 64 * 1024L,
                chunkSize = 64 * 1024L
            )

            val tempFile = ChunkReader.createChunkTempFile(source, descriptor, cacheDir, "backup_test_1")
            assertTrue(tempFile.exists())
            assertEquals(64 * 1024L, tempFile.length())

            val deleted = ChunkReader.deleteTempFile(tempFile)
            assertTrue(deleted)
            assertFalse(tempFile.exists())
        }
    }

    @Test
    fun testCleanTempFilesForBackup() {
        runBlocking {
            val file = createTestFileWithKnownPattern(200 * 1024)
            val source = FileChunkSource(file)
            val cacheDir = tempFolder.newFolder("cache2")

            val d0 = ChunkDescriptor(0, 0, 50 * 1024, 50 * 1024)
            val d1 = ChunkDescriptor(1, 50 * 1024, 100 * 1024, 50 * 1024)

            val temp0 = ChunkReader.createChunkTempFile(source, d0, cacheDir, "backup_batch")
            val temp1 = ChunkReader.createChunkTempFile(source, d1, cacheDir, "backup_batch")

            assertTrue(temp0.exists())
            assertTrue(temp1.exists())

            val cleaned = ChunkReader.cleanTempFilesForBackup(cacheDir, "backup_batch")
            assertEquals(2, cleaned)
            assertFalse(temp0.exists())
            assertFalse(temp1.exists())
        }
    }
}
