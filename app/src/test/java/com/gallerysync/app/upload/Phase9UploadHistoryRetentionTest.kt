package com.gallerysync.app.upload

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.ui.main.screens.uploads.ManageUploadsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9UploadHistoryRetentionTest {

    private lateinit var application: Application
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Preferences.init(application)

        database = Room.inMemoryDatabaseBuilder(application, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DbHolder.database = database
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testCompletedUploadOlderThan24Hours_RemainsInRoom() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()

        val twoDaysAgo = System.currentTimeMillis() - (48 * 60 * 60 * 1000L)
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)

        val oldPhoto = RemotePhoto(
            remoteId = "remote_photo_old_1",
            photoType = "jpg",
            fileName = "IMG_OLD_1.jpg",
            fileSize = 2048576L,
            uploadedAt = twoDaysAgo,
            status = "ACTIVE",
            mediaType = "IMAGE"
        )

        val oldVideo = RemotePhoto(
            remoteId = "remote_video_old_2",
            photoType = "mp4",
            fileName = "VID_OLD_2.mp4",
            fileSize = 15728640L,
            uploadedAt = sevenDaysAgo,
            status = "ACTIVE",
            mediaType = "VIDEO",
            duration = 45000L
        )

        remotePhotoDao.insertAll(oldPhoto, oldVideo)

        // Verify records exist in Room
        val count = remotePhotoDao.getCount()
        assertEquals("Both historical records must be present", 2, count)

        val photoRecord = remotePhotoDao.getById("remote_photo_old_1")
        assertNotNull(photoRecord)
        assertEquals("IMG_OLD_1.jpg", photoRecord?.fileName)

        val videoRecord = remotePhotoDao.getById("remote_video_old_2")
        assertNotNull(videoRecord)
        assertEquals("VID_OLD_2.mp4", videoRecord?.fileName)
        assertEquals("VIDEO", videoRecord?.mediaType)
    }

    @Test
    fun testOpeningUploadsScreen_DoesNotDeleteHistoricalRecords() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()

        val threeDaysAgo = System.currentTimeMillis() - (72 * 60 * 60 * 1000L)
        val oldPhoto = RemotePhoto(
            remoteId = "remote_photo_historical",
            photoType = "jpg",
            fileName = "IMG_HISTORICAL.jpg",
            uploadedAt = threeDaysAgo,
            status = "ACTIVE"
        )
        val oldVideo = RemotePhoto(
            remoteId = "remote_video_historical",
            photoType = "mp4",
            fileName = "VID_HISTORICAL.mp4",
            uploadedAt = threeDaysAgo,
            mediaType = "VIDEO",
            status = "ACTIVE"
        )
        remotePhotoDao.insertAll(oldPhoto, oldVideo)

        assertEquals(2, remotePhotoDao.getCount())

        // Simulate opening the Uploads screen by creating the ViewModel
        val viewModel = ManageUploadsViewModel(application)

        // Historical records must NOT be deleted
        val remainingCount = remotePhotoDao.getCount()
        assertEquals("Historical records must not be deleted on ViewModel initialization", 2, remainingCount)
        assertNotNull(remotePhotoDao.getById("remote_photo_historical"))
        assertNotNull(remotePhotoDao.getById("remote_video_historical"))
    }

    @Test
    fun testMultipleUploadsScreenInitializations_DoNotDeleteRecords() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()

        val tenDaysAgo = System.currentTimeMillis() - (10 * 24 * 60 * 60 * 1000L)
        for (i in 1..5) {
            remotePhotoDao.insertAll(
                RemotePhoto(
                    remoteId = "remote_$i",
                    photoType = "jpg",
                    fileName = "PHOTO_$i.jpg",
                    uploadedAt = tenDaysAgo + (i * 1000L),
                    status = "ACTIVE"
                )
            )
        }
        assertEquals(5, remotePhotoDao.getCount())

        // Simulate opening and navigating back and forth to Uploads screen 3 times
        repeat(3) {
            val vm = ManageUploadsViewModel(application)
        }

        assertEquals("Multiple screen visits must never reduce upload history count", 5, remotePhotoDao.getCount())
    }

    @Test
    fun testPendingAndActiveQueueEntries_RemainUnaffected() = runBlocking {
        val uploadQueueDao = database.uploadQueueDao()

        val pendingItem = UploadQueue(
            localId = "local_pending_1",
            pathUri = "content://media/external/images/media/101",
            contentHash = "hash_pending",
            status = "PENDING",
            workerType = "periodic"
        )
        val activeItem = UploadQueue(
            localId = "local_in_progress_2",
            pathUri = "content://media/external/video/media/102",
            contentHash = "hash_active",
            status = "IN_PROGRESS",
            workerType = "periodic",
            workManagerId = "worker_1"
        )

        uploadQueueDao.insert(pendingItem)
        uploadQueueDao.insert(activeItem)

        // ViewModel creation should not affect pending or in-progress queue items
        val viewModel = ManageUploadsViewModel(application)

        val queueItems = uploadQueueDao.getAllFlow().first()
        assertEquals(2, queueItems.size)
        assertTrue(queueItems.any { it.status == "PENDING" })
        assertTrue(queueItems.any { it.status == "IN_PROGRESS" })
    }

    @Test
    fun testExistingUploadHistoryQueries_ContinueToReturnOldRecords() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()

        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        val historicalItem = RemotePhoto(
            remoteId = "ancient_photo_1",
            photoType = "jpg",
            fileName = "IMG_30_DAYS_AGO.jpg",
            fileSize = 1048576L,
            uploadedAt = thirtyDaysAgo,
            status = "ACTIVE"
        )
        remotePhotoDao.insertAll(historicalItem)

        // Verify Flow query returns old items
        val flowResult = remotePhotoDao.getAllFlow().first()
        assertEquals(1, flowResult.size)
        assertEquals("ancient_photo_1", flowResult[0].remoteId)

        // Verify total size flow accounts for old item
        val totalSize = remotePhotoDao.getTotalSizeFlow().first()
        assertEquals(1048576L, totalSize)

        // Verify total count flow accounts for old item
        val totalCount = remotePhotoDao.getTotalCountFlow().first()
        assertEquals(1, totalCount)
    }
}
