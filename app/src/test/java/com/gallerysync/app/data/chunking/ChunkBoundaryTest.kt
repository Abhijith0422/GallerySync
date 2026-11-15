package com.gallerysync.app.data.chunking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates deterministic chunk boundary calculations across all required boundary conditions:
 * - Empty (0-byte) file handling.
 * - 1 byte.
 * - 15 MiB.
 * - Exactly 16 MiB.
 * - 16 MiB + 1 byte.
 * - 32 MiB.
 * - 51,000,000 bytes (51 MB decimal).
 * - 53,477,376 bytes (51 MiB binary).
 * - 100 MB.
 * - 500 MB.
 * - 1 GiB (1,073,741,824 bytes).
 * - 2 GiB (2,147,483,648 bytes).
 */
class ChunkBoundaryTest {

    private val defaultChunkSize = ChunkConfiguration.DEFAULT_CHUNK_SIZE // 16,777,216 bytes

    private fun verifyInvariants(fileSize: Long, descriptors: List<ChunkDescriptor>) {
        assertTrue("Descriptors cannot be empty for positive fileSize", descriptors.isNotEmpty())

        var expectedStart = 0L
        for (i in descriptors.indices) {
            val d = descriptors[i]
            assertEquals("Chunk index must match sequence", i, d.chunkIndex)
            assertEquals("Start offset must match previous end offset", expectedStart, d.startOffset)
            assertTrue("End offset must be > startOffset", d.endOffset > d.startOffset)
            assertEquals("Chunk size must equal endOffset - startOffset", d.endOffset - d.startOffset, d.chunkSize)

            if (i < descriptors.size - 1) {
                assertEquals("Non-final chunks must equal configured chunkSize", defaultChunkSize, d.chunkSize)
            } else {
                assertTrue("Final chunk must be <= configured chunkSize", d.chunkSize <= defaultChunkSize)
                assertEquals("Final chunk end offset must match fileSize exactly", fileSize, d.endOffset)
            }
            expectedStart = d.endOffset
        }

        val totalSum = descriptors.sumOf { it.chunkSize }
        assertEquals("Sum of all chunk sizes must equal source fileSize with zero gaps", fileSize, totalSum)
    }

    @Test
    fun testZeroByteFileThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkDescriptor.calculateDescriptors(0L)
        }
    }

    @Test
    fun testNegativeFileSizeThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkDescriptor.calculateDescriptors(-100L)
        }
    }

    @Test
    fun testOneByteFile() {
        val descriptors = ChunkDescriptor.calculateDescriptors(1L)
        assertEquals(1, descriptors.size)
        assertEquals(0L, descriptors[0].startOffset)
        assertEquals(1L, descriptors[0].endOffset)
        assertEquals(1L, descriptors[0].chunkSize)
        verifyInvariants(1L, descriptors)
    }

    @Test
    fun testFifteenMib() {
        val size = 15 * 1024 * 1024L // 15,728,640 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(1, descriptors.size)
        assertEquals(size, descriptors[0].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testExactlySixteenMib() {
        val size = 16 * 1024 * 1024L // 16,777,216 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(1, descriptors.size)
        assertEquals(16777216L, descriptors[0].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testSixteenMibPlusOneByte() {
        val size = (16 * 1024 * 1024L) + 1L // 16,777,217 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(2, descriptors.size)
        assertEquals(16777216L, descriptors[0].chunkSize)
        assertEquals(1L, descriptors[1].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testThirtyTwoMib() {
        val size = 32 * 1024 * 1024L // 33,554,432 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(2, descriptors.size)
        assertEquals(16777216L, descriptors[0].chunkSize)
        assertEquals(16777216L, descriptors[1].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testFiftyOneMbDecimal() {
        // 51 MB (decimal) = 51,000,000 bytes
        // 3 * 16 MiB = 50,331,648 bytes; remainder = 668,352 bytes
        val size = 51000000L
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(4, descriptors.size)
        assertEquals(16777216L, descriptors[0].chunkSize)
        assertEquals(16777216L, descriptors[1].chunkSize)
        assertEquals(16777216L, descriptors[2].chunkSize)
        assertEquals(668352L, descriptors[3].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testFiftyOneMibBinary() {
        // 51 MiB (binary) = 51 * 1,048,576 = 53,477,376 bytes
        // 3 * 16 MiB = 50,331,648 bytes; remainder = 3,145,728 bytes (3 MiB)
        val size = 53477376L
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        assertEquals(4, descriptors.size)
        assertEquals(16777216L, descriptors[0].chunkSize)
        assertEquals(16777216L, descriptors[1].chunkSize)
        assertEquals(16777216L, descriptors[2].chunkSize)
        assertEquals(3145728L, descriptors[3].chunkSize)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testOneHundredMb() {
        val size = 100 * 1000 * 1000L // 100,000,000 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        // ceil(100,000,000 / 16,777,216) = 6 chunks
        assertEquals(6, descriptors.size)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testFiveHundredMb() {
        val size = 500 * 1000 * 1000L // 500,000,000 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        // ceil(500,000,000 / 16,777,216) = 30 chunks
        assertEquals(30, descriptors.size)
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testOneGib() {
        val size = 1024L * 1024L * 1024L // 1,073,741,824 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        // 1,073,741,824 / 16,777,216 = exactly 64 chunks
        assertEquals(64, descriptors.size)
        assertTrue(descriptors.all { it.chunkSize == 16777216L })
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testTwoGib() {
        val size = 2L * 1024L * 1024L * 1024L // 2,147,483,648 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(size)
        // 2,147,483,648 / 16,777,216 = exactly 128 chunks
        assertEquals(128, descriptors.size)
        assertTrue(descriptors.all { it.chunkSize == 16777216L })
        verifyInvariants(size, descriptors)
    }

    @Test
    fun testChunkConfigurationEnforcesTelegramMaxChunkSize() {
        // Default 16 MiB is valid
        val defaultConfig = ChunkConfiguration(ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(16 * 1024 * 1024L, defaultConfig.chunkSize)

        // Absolute Telegram getFile ceiling: exactly 20,000,000 bytes is valid
        val maxConfig = ChunkConfiguration(20_000_000L)
        assertEquals(20_000_000L, maxConfig.chunkSize)

        // Minimum 1 MiB is valid
        val minConfig = ChunkConfiguration(1024 * 1024L)
        assertEquals(1024 * 1024L, minConfig.chunkSize)

        // > 20,000,000 bytes (e.g. 20,000,001 or 50 MB) MUST be rejected
        assertThrows(IllegalArgumentException::class.java) {
            ChunkConfiguration(20_000_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChunkConfiguration(50 * 1000 * 1000L)
        }
        // < 1 MiB MUST be rejected
        assertThrows(IllegalArgumentException::class.java) {
            ChunkConfiguration(1024 * 1024L - 1L)
        }
    }

    @Test
    fun testChunkDescriptorCalculationOverflowSafe() {
        // Multi-gigabyte file: 100 GB
        val hundredGb = 100L * 1024L * 1024L * 1024L
        val chunkSize = ChunkConfiguration.DEFAULT_CHUNK_SIZE
        val expectedChunks = 1L + (hundredGb - 1L) / chunkSize
        assertEquals(6400L, expectedChunks)

        // Verify calculateDescriptors calculation matches
        val descriptors = ChunkDescriptor.calculateDescriptors(hundredGb)
        assertEquals(6400, descriptors.size)
        assertEquals(0L, descriptors.first().startOffset)
        assertEquals(hundredGb, descriptors.last().endOffset)
    }
}
