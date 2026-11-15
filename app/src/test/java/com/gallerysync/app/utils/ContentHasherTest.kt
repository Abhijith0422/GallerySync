package com.gallerysync.app.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Arrays

class ContentHasherTest {

    @Test
    fun testFullFileSha256MatchesKnownTestVector() {
        val input = "Hello, GallerySync full-file SHA-256 test!".toByteArray(Charsets.UTF_8)
        val hash = ContentHasher.computeHash(input)
        assertNotNull(hash)
        assertEquals(64, hash.length)

        // Known SHA-256 test vector check: "abc"
        val abcHash = ContentHasher.computeHash("abc".toByteArray(Charsets.UTF_8))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abcHash)
    }

    @Test
    fun testIdenticalStreamsProduceIdenticalHashes() = runBlocking {
        val data = ByteArray(150 * 1024) { (it % 256).toByte() }
        val stream1 = ByteArrayInputStream(data)
        val stream2 = ByteArrayInputStream(data.clone())

        val hash1 = ContentHasher.computeHashFromStream(stream1)
        val hash2 = ContentHasher.computeHashFromStream(stream2)

        assertEquals("Identical byte sequences must produce identical hashes", hash1, hash2)
    }

    @Test
    fun testFilesWithSameHeaderFooterButDifferentMiddleProduceDifferentHashes() = runBlocking {
        val totalSize = 200 * 1024 // 200 KB
        val headerSize = 64 * 1024 // 64 KB header
        val footerSize = 64 * 1024 // 64 KB footer

        val fileA = ByteArray(totalSize)
        val fileB = ByteArray(totalSize)

        // Fill headers identically
        for (i in 0 until headerSize) {
            val b = (i % 128).toByte()
            fileA[i] = b
            fileB[i] = b
        }

        // Fill footers identically
        for (i in (totalSize - footerSize) until totalSize) {
            val b = (i % 128).toByte()
            fileA[i] = b
            fileB[i] = b
        }

        // Fill middle differently
        val middleStart = headerSize
        val middleEnd = totalSize - footerSize
        for (i in middleStart until middleEnd) {
            fileA[i] = 0xAA.toByte()
            fileB[i] = 0xBB.toByte()
        }

        // Verify headers and footers match
        assertTrue(Arrays.equals(fileA.copyOfRange(0, headerSize), fileB.copyOfRange(0, headerSize)))
        assertTrue(Arrays.equals(fileA.copyOfRange(totalSize - footerSize, totalSize), fileB.copyOfRange(totalSize - footerSize, totalSize)))

        // Verify middle differs
        assertFalse(Arrays.equals(fileA.copyOfRange(middleStart, middleEnd), fileB.copyOfRange(middleStart, middleEnd)))

        val hashA = ContentHasher.computeHashFromStream(ByteArrayInputStream(fileA))
        val hashB = ContentHasher.computeHashFromStream(ByteArrayInputStream(fileB))

        assertNotEquals(
            "Full-file SHA-256 must distinguish files even when first and last 64 KB are identical",
            hashA,
            hashB
        )
    }

    @Test
    fun testCoroutineCancellationPreserved() = runBlocking {
        // Infinite/very large stream generator
        val infiniteStream = object : InputStream() {
            override fun read(): Int = 0x42
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Arrays.fill(b, off, off + len, 0x42.toByte())
                return len
            }
        }

        var cancelled = false
        val job = launch(Dispatchers.Default) {
            try {
                ContentHasher.computeHashFromStream(infiniteStream)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        delay(50)
        job.cancelAndJoin()
        assertTrue("Coroutine cancellation must be promptly honored during streaming", cancelled)
    }
}
