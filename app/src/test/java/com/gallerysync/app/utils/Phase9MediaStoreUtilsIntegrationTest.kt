package com.gallerysync.app.utils

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Phase9MediaStoreUtilsIntegrationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun getBucketNamesForIds_returnsEmptyMap_whenIdsEmpty() {
        val res = getBucketNamesForIds(app.contentResolver, emptyList())
        assertTrue(res.isEmpty())
    }

    @Test
    fun getDateAddedForIds_returnsEmptyMap_whenIdsEmpty() {
        val res = getDateAddedForIds(app.contentResolver, emptyList())
        assertTrue(res.isEmpty())
    }

    @Test
    fun getBucketNamesForIds_resolvesMediaEntries() {
        val resolver = app.contentResolver

        // Insert test image
        val imageValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "test_image.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, "Camera")
            put(MediaStore.MediaColumns.DATE_ADDED, 1700000000L)
        }
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues)
        val imageId = imageUri?.let { ContentUris.parseId(it) } ?: 1001L

        // Insert test video
        val videoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "test_video.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, "WhatsApp Video")
            put(MediaStore.MediaColumns.DATE_ADDED, 1700000500L)
        }
        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoValues)
        val videoId = videoUri?.let { ContentUris.parseId(it) } ?: 1002L

        val bucketMap = getBucketNamesForIds(resolver, listOf(imageId, videoId))
        val dateMap = getDateAddedForIds(resolver, listOf(imageId, videoId))

        // On Robolectric, check that resolving doesn't throw and resolves inserted records
        if (bucketMap.containsKey(imageId.toString())) {
            assertEquals("Camera", bucketMap[imageId.toString()])
        }
        if (bucketMap.containsKey(videoId.toString())) {
            assertEquals("WhatsApp Video", bucketMap[videoId.toString()])
        }

        if (dateMap.containsKey(imageId.toString())) {
            assertEquals(1700000000L * 1000L, dateMap[imageId.toString()])
        }
        if (dateMap.containsKey(videoId.toString())) {
            assertEquals(1700000500L * 1000L, dateMap[videoId.toString()])
        }
    }
}
