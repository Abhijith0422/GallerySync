package com.gallerysync.app.data.chunking

import android.net.Uri
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
import java.io.InputStream

/**
 * Validates SourceModificationDetector and pre/post hashing immutability verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceModificationTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testUnchangedMetadataReportsUnchanged() {
        val meta1 = SourceMetadata(
            uri = Uri.parse("content://media/1"),
            fileSize = 1000L,
            dateModified = 5000L,
            displayName = "vid.mp4",
            mimeType = "video/mp4"
        )
        val meta2 = meta1.copy()

        val result = SourceModificationDetector.detectChanges(meta1, meta2)
        assertEquals(SourceModificationDetector.ChangeResult.Unchanged, result)
    }

    @Test
    fun testFileSizeChangeDetected() {
        val meta1 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1000L)
        val meta2 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1200L)

        val result = SourceModificationDetector.detectChanges(meta1, meta2)
        assertTrue(result is SourceModificationDetector.ChangeResult.Modified)
        val mod = result as SourceModificationDetector.ChangeResult.Modified
        assertEquals("fileSize", mod.field)
        assertEquals(1000L, mod.expected)
        assertEquals(1200L, mod.actual)
    }

    @Test
    fun testDateModifiedChangeDetected() {
        val meta1 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1000L, dateModified = 1000L)
        val meta2 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1000L, dateModified = 2000L)

        val result = SourceModificationDetector.detectChanges(meta1, meta2)
        assertTrue(result is SourceModificationDetector.ChangeResult.Modified)
        val mod = result as SourceModificationDetector.ChangeResult.Modified
        assertEquals("dateModified", mod.field)
        assertEquals(1000L, mod.expected)
        assertEquals(2000L, mod.actual)
    }

    @Test
    fun testDisplayNameChangeDetected() {
        val meta1 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1000L, displayName = "old.mp4")
        val meta2 = SourceMetadata(Uri.parse("content://media/1"), fileSize = 1000L, displayName = "new.mp4")

        val result = SourceModificationDetector.detectChanges(meta1, meta2)
        assertTrue(result is SourceModificationDetector.ChangeResult.Modified)
        val mod = result as SourceModificationDetector.ChangeResult.Modified
        assertEquals("displayName", mod.field)
    }

    @Test
    fun testSourceModifiedDuringHashingAbortsWithResult() = runBlocking {
        var queryCount = 0
        val file = tempFolder.newFile("mod_test.bin")
        FileOutputStream(file).use { fos ->
            fos.write(ByteArray(100 * 1024) { 1 })
        }

        val dynamicSource = object : ChunkSource {
            override val uri: Uri = Uri.fromFile(file)
            override suspend fun getMetadata(): SourceMetadata {
                queryCount++
                return if (queryCount <= 1) {
                    SourceMetadata(uri, fileSize = 100 * 1024L, dateModified = 1000L)
                } else {
                    SourceMetadata(uri, fileSize = 120 * 1024L, dateModified = 2000L)
                }
            }

            override suspend fun validate(): SourceValidationResult = SourceValidationResult.Valid
            override suspend fun openStream(): InputStream = file.inputStream()
            override suspend fun openSeekableChannel() = file.inputStream().channel
            override fun isSeekable(): Boolean = true
            override fun close() {}
        }

        val result = StreamingChunkHasher.hash(dynamicSource)
        assertTrue(
            "Source modified during hashing must return HashingResult.SourceModified",
            result is HashingResult.SourceModified
        )
    }
}
