package com.gallerysync.app.ui.main

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.mediastore.ScannedMediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PullToRefreshGalleryTest {

    private lateinit var context: Application
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.allowUnencryptedFallbackForTesting = true
        Preferences.init(context)

        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DbHolder.database = database
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testHoldingMediaNeverReleasedDuringRefresh(): Unit = runBlocking {
        val photoDao = database.photoDao()
        val baselineTime = 100_000L
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 5, timestamp = baselineTime)

        // Seed 2 existing media items in HOLDING state
        val holdingPhoto1 = Photo(
            localId = "holding_1",
            pathUri = "content://media/external/images/media/1",
            photoType = "jpg",
            uploadStatus = Photo.STATUS_HOLDING,
            mediaType = "IMAGE"
        )
        val holdingPhoto2 = Photo(
            localId = "holding_2",
            pathUri = "content://media/external/images/media/2",
            photoType = "jpg",
            uploadStatus = Photo.STATUS_HOLDING,
            mediaType = "IMAGE"
        )
        photoDao.insertPhotos(holdingPhoto1, holdingPhoto2)

        // Simulate a refresh scan discovering holding_1 and holding_2 again
        val rescannedBatch = listOf(
            ScannedMediaItem(
                id = "holding_1",
                contentUri = "content://media/external/images/media/1",
                mimeType = "image/jpeg",
                photoType = "jpg",
                size = 1024L,
                dateAdded = 50L,
                dateModified = 50L,
                dateTaken = 50_000L,
                mediaType = "IMAGE",
                width = 1080,
                height = 1920,
                duration = null,
                bucketId = 1L,
                bucketName = "Camera",
                deviceId = "device_test"
            ),
            ScannedMediaItem(
                id = "holding_2",
                contentUri = "content://media/external/images/media/2",
                mimeType = "image/jpeg",
                photoType = "jpg",
                size = 2048L,
                dateAdded = 60L,
                dateModified = 60L,
                dateTaken = 60_000L,
                mediaType = "IMAGE",
                width = 1080,
                height = 1920,
                duration = null,
                bucketId = 1L,
                bucketName = "Camera",
                deviceId = "device_test"
            )
        )

        // During refresh, insert with OnConflictStrategy.IGNORE
        val isBaselineDone = InstallRecoveryManager.isBaselineCompleted(database)
        val baselineTimestamp = InstallRecoveryManager.getBaselineTimestamp(database)
        val photosToInsert = rescannedBatch.map { item ->
            val itemTimestampMs = maxOf(item.dateAdded, item.dateModified) * 1000L
            val status = if (!isBaselineDone || (baselineTimestamp > 0L && itemTimestampMs <= baselineTimestamp)) {
                Photo.STATUS_HOLDING
            } else {
                Photo.STATUS_NONE
            }
            item.toPhoto(defaultUploadStatus = status)
        }
        photoDao.insertPhotos(*photosToInsert.toTypedArray())

        // Verify status remains strictly HOLDING
        val p1 = photoDao.getPhotoByLocalId("holding_1")
        val p2 = photoDao.getPhotoByLocalId("holding_2")
        assertNotNull(p1)
        assertNotNull(p2)
        assertEquals(Photo.STATUS_HOLDING, p1?.uploadStatus)
        assertEquals(Photo.STATUS_HOLDING, p2?.uploadStatus)

        // Verify upload queue is completely empty
        val queueCount = database.uploadQueueDao().getPending().size
        assertEquals(0, queueCount)
    }

    @Test
    fun testNewMediaDiscoveredAfterBaselineGetsStatusNone(): Unit = runBlocking {
        val photoDao = database.photoDao()
        val baselineTime = 100_000L
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 10, timestamp = baselineTime)

        // Simulate a new photo taken after baseline (timestamp 200s = 200,000ms > 100,000ms)
        val newPhoto = ScannedMediaItem(
            id = "new_photo_post_baseline",
            contentUri = "content://media/external/images/media/3",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 4096L,
            dateAdded = 200L,
            dateModified = 200L,
            dateTaken = 200_000L,
            mediaType = "IMAGE",
            width = 1920,
            height = 1080,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "device_test"
        )

        val isBaselineDone = InstallRecoveryManager.isBaselineCompleted(database)
        val baselineTimestamp = InstallRecoveryManager.getBaselineTimestamp(database)
        val itemTimestampMs = maxOf(newPhoto.dateAdded, newPhoto.dateModified) * 1000L

        val status = if (!isBaselineDone || (baselineTimestamp > 0L && itemTimestampMs <= baselineTimestamp)) {
            Photo.STATUS_HOLDING
        } else {
            Photo.STATUS_NONE
        }
        photoDao.insertPhotos(newPhoto.toPhoto(defaultUploadStatus = status))

        val indexed = photoDao.getPhotoByLocalId("new_photo_post_baseline")
        assertNotNull(indexed)
        assertEquals(Photo.STATUS_NONE, indexed?.uploadStatus)
    }

    @Test
    fun testPreBaselineMediaDiscoveredDuringRefreshGetsStatusHolding(): Unit = runBlocking {
        val photoDao = database.photoDao()
        val baselineTime = 500_000L
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 50, timestamp = baselineTime)

        // An older media item that was created prior to baseline (250s = 250,000ms <= 500,000ms)
        val olderPreBaselinePhoto = ScannedMediaItem(
            id = "pre_baseline_late_discovery",
            contentUri = "content://media/external/images/media/4",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 3000L,
            dateAdded = 250L,
            dateModified = 250L,
            dateTaken = 250_000L,
            mediaType = "IMAGE",
            width = 1080,
            height = 1920,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "device_test"
        )

        val isBaselineDone = InstallRecoveryManager.isBaselineCompleted(database)
        val baselineTimestamp = InstallRecoveryManager.getBaselineTimestamp(database)
        val itemTimestampMs = maxOf(olderPreBaselinePhoto.dateAdded, olderPreBaselinePhoto.dateModified) * 1000L

        val status = if (!isBaselineDone || (baselineTimestamp > 0L && itemTimestampMs <= baselineTimestamp)) {
            Photo.STATUS_HOLDING
        } else {
            Photo.STATUS_NONE
        }
        photoDao.insertPhotos(olderPreBaselinePhoto.toPhoto(defaultUploadStatus = status))

        val indexed = photoDao.getPhotoByLocalId("pre_baseline_late_discovery")
        assertNotNull(indexed)
        assertEquals(Photo.STATUS_HOLDING, indexed?.uploadStatus)
    }

    @Test
    fun testConcurrentRefreshGuarding(): Unit = runBlocking {
        val viewModel = MainViewModel(context)
        assertFalse(viewModel.isRefreshing.value)

        // Trigger refresh
        viewModel.refreshGallery()
        assertTrue(viewModel.isRefreshing.value)

        // Attempt second concurrent refresh
        viewModel.refreshGallery()
        // Still true, no crash or duplicate launch
        assertTrue(viewModel.isRefreshing.value)
    }
}
