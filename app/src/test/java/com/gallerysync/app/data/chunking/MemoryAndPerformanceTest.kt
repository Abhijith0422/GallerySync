package com.gallerysync.app.data.chunking

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * Validates bounded memory and streaming performance on large virtual streams (100 MB, 500 MB, 1 GB):
 * - Guarantees that peak heap usage does not scale with stream size.
 * - Confirms that 1 GB streams can be hashed in bounded memory (64 KB buffer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryAndPerformanceTest {

    /**
     * A zero-allocation synthetic stream that produces [totalBytes] of deterministic bytes on the fly.
     */
    class SyntheticByteStream(private val totalBytes: Long) : InputStream() {
        private var current = 0L

        override fun read(): Int {
            if (current >= totalBytes) return -1
            val b = (current % 256).toInt()
            current++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (current >= totalBytes) return -1
            val available = totalBytes - current
            val toRead = minOf(len.toLong(), available).toInt()
            for (i in 0 until toRead) {
                b[off + i] = ((current + i) % 256).toByte()
            }
            current += toRead
            return toRead
        }
    }

    private fun createSyntheticSource(size: Long): ChunkSource {
        val metadata = SourceMetadata(
            uri = Uri.parse("content://synthetic/$size"),
            fileSize = size,
            dateModified = 1700000000L,
            displayName = "synthetic_$size.mp4",
            mimeType = "video/mp4"
        )
        return StreamChunkSource(
            uri = metadata.uri,
            metadata = metadata,
            streamProvider = { SyntheticByteStream(size) }
        )
    }

    @Test
    fun testOneHundredMegabytesBoundedMemory() = runBlocking {
        val size = 100 * 1000 * 1000L // 100 MB decimal
        val source = createSyntheticSource(size)

        System.gc()
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()

        val startTime = System.currentTimeMillis()
        val result = StreamingChunkHasher.hash(source)
        val durationMs = System.currentTimeMillis() - startTime

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success
        assertEquals(size, success.totalBytesRead)
        assertEquals(6, success.chunkDescriptors.size) // ceil(100 MB / 16 MiB) = 6

        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val heapGrowthMb = (heapAfter - heapBefore) / (1024 * 1024)

        // Heap should not grow anywhere near 100 MB (should stay well below 25 MB)
        assertTrue("Heap growth ($heapGrowthMb MB) must remain bounded", heapGrowthMb < 25)
        println("100 MB streaming hash: duration = ${durationMs}ms, heap growth = ${heapGrowthMb}MB")
    }

    @Test
    fun testFiveHundredMegabytesBoundedMemory() = runBlocking {
        val size = 500 * 1000 * 1000L // 500 MB
        val source = createSyntheticSource(size)

        System.gc()
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()

        val startTime = System.currentTimeMillis()
        val result = StreamingChunkHasher.hash(source)
        val durationMs = System.currentTimeMillis() - startTime

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success
        assertEquals(size, success.totalBytesRead)
        assertEquals(30, success.chunkDescriptors.size) // ceil(500 MB / 16 MiB) = 30

        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val heapGrowthMb = (heapAfter - heapBefore) / (1024 * 1024)

        assertTrue("Heap growth ($heapGrowthMb MB) must remain bounded for 500 MB stream", heapGrowthMb < 30)
        println("500 MB streaming hash: duration = ${durationMs}ms, heap growth = ${heapGrowthMb}MB")
    }

    @Test
    fun testOneGigabyteBoundedMemory() = runBlocking {
        val size = 1024L * 1024L * 1024L // 1 GiB
        val source = createSyntheticSource(size)

        System.gc()
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()

        val startTime = System.currentTimeMillis()
        val result = StreamingChunkHasher.hash(source)
        val durationMs = System.currentTimeMillis() - startTime

        assertTrue(result is HashingResult.Success)
        val success = result as HashingResult.Success
        assertEquals(size, success.totalBytesRead)
        assertEquals(64, success.chunkDescriptors.size) // 1 GiB / 16 MiB = 64 chunks

        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val heapGrowthMb = (heapAfter - heapBefore) / (1024 * 1024)

        assertTrue("Heap growth ($heapGrowthMb MB) must remain bounded for 1 GiB stream", heapGrowthMb < 35)
        println("1 GiB streaming hash: duration = ${durationMs}ms, heap growth = ${heapGrowthMb}MB")
    }
}
