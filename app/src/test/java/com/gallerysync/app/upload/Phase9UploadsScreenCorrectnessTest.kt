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
import com.gallerysync.app.ui.main.screens.uploads.DurationFormatter
import com.gallerysync.app.ui.main.screens.uploads.ManageUploadsViewModel
import com.gallerysync.app.ui.main.screens.uploads.UploadStatus
import com.gallerysync.app.ui.main.screens.uploads.UploadType
import com.gallerysync.app.ui.main.screens.uploads.UploadUiItem
import com.gallerysync.app.utils.coil.FileIdData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase9UploadsScreenCorrectnessTest {

    private lateinit var application: Application
    private lateinit var database: WhDatabase
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Preferences.init(application)
        testScope = CoroutineScope(Dispatchers.Unconfined)

        database = Room.inMemoryDatabaseBuilder(application, WhDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Dispatchers.Unconfined.asExecutor())
            .setTransactionExecutor(Dispatchers.Unconfined.asExecutor())
            .build()
        DbHolder.database = database
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun <T> StateFlow<List<T>>.awaitItems(
        predicate: (List<T>) -> Boolean = { it.isNotEmpty() }
    ): List<T> {
        return withTimeout(5000L) {
            first { predicate(it) }
        }
    }

    /**
     * 1. Video UploadUiItem metadata mapping from RemotePhoto.
     */
    @Test
    fun testVideoUploadUiItemMetadataMapping_fromRemotePhoto() = runBlocking {
        val viewModel = ManageUploadsViewModel(application, testScope)
        val remoteVideo = RemotePhoto(
            remoteId = "remote_vid_101",
            photoType = "mp4",
            fileName = "VID_20261115_001.mp4",
            fileSize = 25000000L,
            uploadedAt = 1700000000000L,
            mediaType = "VIDEO",
            previewRemoteId = "thumb_fid_999",
            duration = 125000L, // 2m 5s in ms
            width = 1920,
            height = 1080
        )

        val uiItem = viewModel.mapRemotePhotoToUiItem(
            remote = remoteVideo,
            localPathMap = emptyMap(),
            localIdMap = emptyMap()
        )

        assertTrue("Item must be marked as video", uiItem.isVideo)
        assertEquals("Duration must match milliseconds", 125000L, uiItem.duration)
        assertEquals("Width must match", 1920, uiItem.width)
        assertEquals("Height must match", 1080, uiItem.height)
        assertEquals("FileName must match", "VID_20261115_001.mp4", uiItem.fileName)
        assertTrue("Thumbnail must wrap previewRemoteId in FileIdData", uiItem.thumbnailUri is FileIdData)
        assertEquals("thumb_fid_999", (uiItem.thumbnailUri as FileIdData).fileId)
    }

    /**
     * 2. Video UploadUiItem metadata mapping from UploadQueue.
     */
    @Test
    fun testVideoUploadUiItemMetadataMapping_fromUploadQueue() = runBlocking {
        val uploadQueueDao = database.uploadQueueDao()
        val videoQueue = UploadQueue(
            localId = "local_vid_202",
            pathUri = "content://media/external/video/media/202",
            contentHash = "hash_vid_202",
            status = "PENDING",
            workerType = "periodic",
            mediaType = "VIDEO",
            duration = 74000L, // 1m 14s in ms
            width = 1280,
            height = 720
        )
        uploadQueueDao.insert(videoQueue)

        val viewModel = ManageUploadsViewModel(application, testScope)
        val allItems = viewModel.allUploads.awaitItems { it.any { item -> item.localId == "local_vid_202" } }

        val foundVideo = allItems.find { it.localId == "local_vid_202" }
        assertNotNull("Queue video must be present in allUploads", foundVideo)
        assertTrue("Queue item must be marked as video", foundVideo!!.isVideo)
        assertEquals("Duration must be 74000L", 74000L, foundVideo.duration)
        assertEquals("Width must be 1280", 1280, foundVideo.width)
        assertEquals("Height must be 720", 720, foundVideo.height)
        assertEquals("Status must be Queued", UploadStatus.Queued, foundVideo.status)
    }

    /**
     * 3. Photo UploadUiItem metadata mapping.
     */
    @Test
    fun testPhotoUploadUiItemMetadataMapping() = runBlocking {
        val viewModel = ManageUploadsViewModel(application, testScope)
        val remotePhoto = RemotePhoto(
            remoteId = "remote_photo_303",
            photoType = "jpg",
            fileName = "IMG_20261115_001.jpg",
            fileSize = 3500000L,
            uploadedAt = 1700000000000L,
            mediaType = "IMAGE",
            previewRemoteId = "preview_fid_303",
            duration = null,
            width = 4032,
            height = 3024
        )

        val uiItem = viewModel.mapRemotePhotoToUiItem(
            remote = remotePhoto,
            localPathMap = emptyMap(),
            localIdMap = emptyMap()
        )

        assertFalse("Item must NOT be marked as video", uiItem.isVideo)
        assertNull("Photo duration must be null", uiItem.duration)
        assertEquals("Width must be 4032", 4032, uiItem.width)
        assertEquals("Height must be 3024", 3024, uiItem.height)
        assertEquals("IMG_20261115_001.jpg", uiItem.fileName)
        assertEquals("preview_fid_303", (uiItem.thumbnailUri as? FileIdData)?.fileId)
    }

    /**
     * 4. Duration formatting verification.
     * Expected examples:
     * - 0 seconds -> 00:00
     * - 74 seconds -> 01:14
     * - 125 seconds -> 02:05
     * - 3661 seconds -> 1:01:01
     * Null, zero, and invalid durations handled safely.
     */
    @Test
    fun testDurationFormatting() {
        // Milliseconds tests (database representation)
        assertEquals("00:00", DurationFormatter.formatDurationMillis(0L))
        assertEquals("01:14", DurationFormatter.formatDurationMillis(74_000L))
        assertEquals("02:05", DurationFormatter.formatDurationMillis(125_000L))
        assertEquals("1:01:01", DurationFormatter.formatDurationMillis(3_661_000L))
        assertEquals("00:00", DurationFormatter.formatDurationMillis(null))
        assertEquals("00:00", DurationFormatter.formatDurationMillis(-500L))

        // Seconds tests
        assertEquals("00:00", DurationFormatter.formatDurationSeconds(0L))
        assertEquals("01:14", DurationFormatter.formatDurationSeconds(74L))
        assertEquals("02:05", DurationFormatter.formatDurationSeconds(125L))
        assertEquals("1:01:01", DurationFormatter.formatDurationSeconds(3661L))
        assertEquals("00:00", DurationFormatter.formatDurationSeconds(null))
        assertEquals("00:00", DurationFormatter.formatDurationSeconds(-10L))

        // UploadUiItem companion delegator
        assertEquals("00:00", UploadUiItem.formatDuration(0L))
        assertEquals("01:14", UploadUiItem.formatDuration(74_000L))
        assertEquals("02:05", UploadUiItem.formatDuration(125_000L))
        assertEquals("1:01:01", UploadUiItem.formatDuration(3_661_000L))
        assertEquals("00:00", UploadUiItem.formatDuration(null))
        assertEquals("00:00", UploadUiItem.formatDuration(-1L))
    }

    /**
     * 5. Completed background video appears in Synced.
     */
    @Test
    fun testCompletedBackgroundVideoAppearsInSynced() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()
        val bgVideo = RemotePhoto(
            remoteId = "remote_bg_vid_1",
            photoType = "mp4",
            fileName = "VID_BG_DONE.mp4",
            fileSize = 12000000L,
            uploadedAt = System.currentTimeMillis() - 10000L,
            uploadType = "periodic",
            status = "ACTIVE",
            mediaType = "VIDEO",
            previewRemoteId = "thumb_bg_1",
            duration = 45000L
        )
        remotePhotoDao.insertAll(bgVideo)

        val viewModel = ManageUploadsViewModel(application, testScope)
        val syncedItems = viewModel.syncedUploads.awaitItems { it.any { item -> item.id == "remote_bg_vid_1" } }

        val found = syncedItems.find { it.id == "remote_bg_vid_1" }
        assertNotNull("Completed background video must appear in syncedUploads", found)
        assertEquals(UploadStatus.Completed, found!!.status)
        assertTrue(found.isVideo)
        assertEquals(45000L, found.duration)
    }

    /**
     * 6. Completed background video appears in All.
     */
    @Test
    fun testCompletedBackgroundVideoAppearsInAll() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()
        val bgVideo = RemotePhoto(
            remoteId = "remote_bg_vid_2",
            photoType = "mp4",
            fileName = "VID_BG_DONE_2.mp4",
            fileSize = 18000000L,
            uploadedAt = System.currentTimeMillis() - 5000L,
            uploadType = "periodic",
            status = "ACTIVE",
            mediaType = "VIDEO",
            previewRemoteId = "thumb_bg_2",
            duration = 60000L
        )
        remotePhotoDao.insertAll(bgVideo)

        val viewModel = ManageUploadsViewModel(application, testScope)
        val allItems = viewModel.allUploads.awaitItems { it.any { item -> item.id == "remote_bg_vid_2" } }

        val found = allItems.find { it.id == "remote_bg_vid_2" }
        assertNotNull("Completed background video must appear in allUploads", found)
        assertEquals(UploadStatus.Completed, found!!.status)
        assertTrue(found.isVideo)
    }

    /**
     * 7. Manual tab classification remains correct.
     * Only manual/instant uploads appear; background uploads are excluded.
     */
    @Test
    fun testManualTabClassificationRemainsCorrect() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()
        val manualPhoto = RemotePhoto(
            remoteId = "manual_photo_1",
            photoType = "jpg",
            fileName = "IMG_MANUAL.jpg",
            uploadedAt = System.currentTimeMillis(),
            uploadType = "manual_backup",
            mediaType = "IMAGE"
        )
        val instantVideo = RemotePhoto(
            remoteId = "instant_video_1",
            photoType = "mp4",
            fileName = "VID_INSTANT.mp4",
            uploadedAt = System.currentTimeMillis(),
            uploadType = "instant",
            mediaType = "VIDEO",
            duration = 30000L
        )
        val autoBackupVideo = RemotePhoto(
            remoteId = "auto_backup_video_1",
            photoType = "mp4",
            fileName = "VID_AUTO.mp4",
            uploadedAt = System.currentTimeMillis(),
            uploadType = "periodic",
            mediaType = "VIDEO",
            duration = 90000L
        )
        remotePhotoDao.insertAll(manualPhoto, instantVideo, autoBackupVideo)

        val viewModel = ManageUploadsViewModel(application, testScope)
        val manualItems = viewModel.manualUploads.awaitItems { it.any { item -> item.id == "manual_photo_1" } }

        assertTrue("manual_backup must appear in manualUploads", manualItems.any { it.id == "manual_photo_1" })
        assertTrue("instant upload must appear in manualUploads", manualItems.any { it.id == "instant_video_1" })
        assertFalse("periodic auto backup must NOT appear in manualUploads", manualItems.any { it.id == "auto_backup_video_1" })
    }

    /**
     * 8. Pending video remains in Queue & Backup, and completed items are not misclassified as pending.
     */
    @Test
    fun testPendingVideoRemainsInQueueAndBackup() = runBlocking {
        val uploadQueueDao = database.uploadQueueDao()
        val pendingVideo = UploadQueue(
            localId = "local_pending_vid_8",
            pathUri = "content://media/external/video/media/808",
            contentHash = "hash_vid_808",
            status = "PENDING",
            workerType = "periodic",
            mediaType = "VIDEO",
            duration = 42000L
        )
        val inProgressVideo = UploadQueue(
            localId = "local_progress_vid_9",
            pathUri = "content://media/external/video/media/909",
            contentHash = "hash_vid_909",
            status = "IN_PROGRESS",
            workerType = "periodic",
            mediaType = "VIDEO",
            duration = 55000L
        )
        val completedVideo = UploadQueue(
            localId = "local_done_vid_10",
            pathUri = "content://media/external/video/media/1010",
            contentHash = "hash_vid_1010",
            status = "COMPLETED",
            workerType = "periodic",
            mediaType = "VIDEO",
            duration = 15000L
        )

        uploadQueueDao.insert(pendingVideo)
        uploadQueueDao.insert(inProgressVideo)
        uploadQueueDao.insert(completedVideo)

        val viewModel = ManageUploadsViewModel(application, testScope)
        val backupItems = viewModel.autoBackupUploads.awaitItems { it.any { item -> item.localId == "local_pending_vid_8" } }

        assertTrue("Pending video must be in autoBackupUploads", backupItems.any { it.localId == "local_pending_vid_8" })
        assertTrue("In-progress video must be in autoBackupUploads", backupItems.any { it.localId == "local_progress_vid_9" })
        assertFalse("Completed video must NOT be in autoBackupUploads", backupItems.any { it.localId == "local_done_vid_10" })

        val pendingItem = backupItems.find { it.localId == "local_pending_vid_8" }!!
        assertEquals("Pending item status must be Queued", UploadStatus.Queued, pendingItem.status)
        assertTrue("Pending item must be video", pendingItem.isVideo)
    }

    /**
     * 9. Missing video thumbnail does not remove the entry.
     */
    @Test
    fun testMissingVideoThumbnailDoesNotRemoveEntry() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()
        val videoWithoutThumb = RemotePhoto(
            remoteId = "remote_vid_no_thumb",
            photoType = "mp4",
            fileName = "VID_NO_THUMB.mp4",
            fileSize = 5000000L,
            uploadedAt = System.currentTimeMillis(),
            uploadType = "periodic",
            status = "ACTIVE",
            mediaType = "VIDEO",
            previewRemoteId = null, // No thumbnail available
            duration = 33000L
        )
        remotePhotoDao.insertAll(videoWithoutThumb)

        val viewModel = ManageUploadsViewModel(application, testScope)

        val allItems = viewModel.allUploads.awaitItems { it.any { item -> item.id == "remote_vid_no_thumb" } }
        val foundInAll = allItems.find { it.id == "remote_vid_no_thumb" }
        assertNotNull("Video without thumbnail must still be in allUploads", foundInAll)
        assertNull("thumbnailUri must be null when previewRemoteId is null", foundInAll!!.thumbnailUri)
        assertTrue("Item must still be marked as video", foundInAll.isVideo)
        assertEquals(33000L, foundInAll.duration)

        val syncedItems = viewModel.syncedUploads.awaitItems { it.any { item -> item.id == "remote_vid_no_thumb" } }
        val foundInSynced = syncedItems.find { it.id == "remote_vid_no_thumb" }
        assertNotNull("Video without thumbnail must still be in syncedUploads", foundInSynced)
    }

    /**
     * 10. Existing image filtering and presentation remain intact across all tabs.
     */
    @Test
    fun testExistingImageFilteringAndPresentationIntact() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()
        val photo1 = RemotePhoto(
            remoteId = "photo_manual",
            photoType = "jpg",
            fileName = "PHOTO_MANUAL.jpg",
            uploadedAt = System.currentTimeMillis() - 2000L,
            uploadType = "manual_backup",
            mediaType = "IMAGE"
        )
        val photo2 = RemotePhoto(
            remoteId = "photo_auto",
            photoType = "png",
            fileName = "PHOTO_AUTO.png",
            uploadedAt = System.currentTimeMillis() - 1000L,
            uploadType = "periodic",
            mediaType = "IMAGE"
        )
        remotePhotoDao.insertAll(photo1, photo2)

        val viewModel = ManageUploadsViewModel(application, testScope)

        // All tab contains both images
        val allItems = viewModel.allUploads.awaitItems { it.size >= 2 }
        assertTrue("photo_manual must be in allUploads", allItems.any { it.id == "photo_manual" })
        assertTrue("photo_auto must be in allUploads", allItems.any { it.id == "photo_auto" })
        assertFalse(allItems.find { it.id == "photo_manual" }!!.isVideo)
        assertFalse(allItems.find { it.id == "photo_auto" }!!.isVideo)

        // Manual tab contains only photo_manual
        val manualItems = viewModel.manualUploads.awaitItems { it.any { item -> item.id == "photo_manual" } }
        assertTrue("photo_manual must be in manualUploads", manualItems.any { it.id == "photo_manual" })
        assertFalse("photo_auto must NOT be in manualUploads", manualItems.any { it.id == "photo_auto" })

        // Synced tab contains both images
        val syncedItems = viewModel.syncedUploads.awaitItems { it.size >= 2 }
        assertTrue("photo_manual must be in syncedUploads", syncedItems.any { it.id == "photo_manual" })
        assertTrue("photo_auto must be in syncedUploads", syncedItems.any { it.id == "photo_auto" })
    }
}
