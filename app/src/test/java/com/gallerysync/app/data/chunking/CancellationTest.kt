package com.gallerysync.app.data.chunking

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Validates coroutine cancellation behavior, resource closure, and temporary file cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CancellationTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    /**
     * A throttled stream that cooperates with coroutine reads and tracks closure.
     */
    class PausableStream(private val closedFlag: AtomicBoolean) : InputStream() {
        override fun read(): Int {
            Thread.sleep(2)
            return 42
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(5)
            b.fill(42, off, off + len)
            return len
        }

        override fun close() {
            closedFlag.set(true)
            super.close()
        }
    }

    @Test
    fun testCancellationDuringFullFileHashingClosesStreamAndThrows() = runBlocking {
        val closedFlag = AtomicBoolean(false)
        val metadata = SourceMetadata(
            uri = Uri.parse("content://test/cancel"),
            fileSize = 100 * 1024 * 1024L // 100 MB
        )
        val source = StreamChunkSource(
            uri = metadata.uri,
            metadata = metadata,
            streamProvider = { PausableStream(closedFlag) }
        )

        var caughtCancellation = false
        val job = launch {
            try {
                StreamingChunkHasher.hash(source)
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        delay(30)
        job.cancelAndJoin()

        assertTrue("Coroutine job was cancelled", job.isCancelled)
        assertTrue("Stream must be closed on cancellation", closedFlag.get())
        assertTrue("CancellationException must be propagated, not swallowed", caughtCancellation)
    }

    @Test
    fun testCancellationDuringChunkReadingCleansTemporaryFile() = runBlocking {
        val closedFlag = AtomicBoolean(false)
        val metadata = SourceMetadata(
            uri = Uri.parse("content://test/chunk_cancel"),
            fileSize = 50 * 1024 * 1024L
        )
        val source = StreamChunkSource(
            uri = metadata.uri,
            metadata = metadata,
            streamProvider = { PausableStream(closedFlag) }
        )

        val cacheDir = tempFolder.newFolder("cancel_cache")
        val descriptor = ChunkDescriptor(
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 16 * 1024 * 1024L,
            chunkSize = 16 * 1024 * 1024L
        )

        val job = launch {
            try {
                ChunkReader.createChunkTempFile(source, descriptor, cacheDir, "backup_cancel")
            } catch (_: CancellationException) {
                // Expected
            }
        }

        delay(30)
        job.cancelAndJoin()

        // Verify temp file does not remain on disk after cancellation
        val expectedFile = java.io.File(cacheDir, "chunks/temp_backup_cancel_chunk_0.bin")
        assertFalse("Incomplete temporary chunk file must be cleaned up on cancellation", expectedFile.exists())
    }
}
