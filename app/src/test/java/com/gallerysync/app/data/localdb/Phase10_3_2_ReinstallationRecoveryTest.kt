package com.gallerysync.app.data.localdb

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.data.localdb.backup.BackupFile
import com.gallerysync.app.data.localdb.backup.ExportedMediaRecord
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.mediastore.ScannedMediaItem
import com.gallerysync.app.data.upload.UploadQueueRepository
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase10_3_2_ReinstallationRecoveryTest {

    private lateinit var context: Context
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

    // 1. Fresh installation with existing MediaStore images
    @Test
    fun test1_freshInstallationWithExistingMediaStoreImages() = runBlocking {
        // Given a fresh installation without baseline
        assertFalse(InstallRecoveryManager.isBaselineCompleted(database))

        // When scanned media items are mapped during the initial baseline scan
        val scannedItem = ScannedMediaItem(
            id = "101",
            contentUri = "content://media/external/images/media/101",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 2048L,
            dateAdded = 1000L,
            dateModified = 1000L,
            dateTaken = 1000L,
            mediaType = "IMAGE",
            width = 1920,
            height = 1080,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "test_device"
        )

        val photo = scannedItem.toPhoto(defaultUploadStatus = Photo.STATUS_HOLDING)
        database.photoDao().insertPhotos(photo)

        // Then photo is stored with HOLDING status and no remoteId
        val saved = database.photoDao().getPhotoByLocalId("101")
        assertNotNull(saved)
        assertEquals(Photo.STATUS_HOLDING, saved!!.uploadStatus)
        assertNull(saved.remoteId)

        // And it is NOT considered pending upload
        val pending = database.photoDao().getAllPendingUpload()
        assertTrue("Holding photos must not be in pending upload queue", pending.isEmpty())
    }

    // 2. Existing installation with valid database history
    @Test
    fun test2_existingInstallationWithValidDatabaseHistory() = runBlocking {
        // Given an existing database with valid RemotePhoto records
        val remotePhoto = RemotePhoto(
            remoteId = "tg_file_999",
            photoType = "jpg",
            fileName = "IMG_999.jpg",
            contentHash = "hash999",
            uploadedAt = 5000L,
            status = "ACTIVE"
        )
        database.remotePhotoDao().insertAll(remotePhoto)

        val existingPhoto = Photo(
            localId = "201",
            remoteId = "tg_file_999",
            photoType = "jpg",
            pathUri = "content://media/external/images/media/201",
            contentHash = "hash999",
            uploadStatus = Photo.STATUS_DONE
        )
        database.photoDao().insertPhotos(existingPhoto)

        // When checkAndInitializeBaseline runs
        val result = InstallRecoveryManager.checkAndInitializeBaseline(context, database)

        // Then it recognizes valid history and preserves existing status
        assertEquals(InstallRecoveryManager.BaselineState.EXISTING_HISTORY_VALID, result)
        val preserved = database.photoDao().getPhotoByLocalId("201")
        assertEquals(Photo.STATUS_DONE, preserved!!.uploadStatus)
        assertEquals("tg_file_999", preserved.remoteId)
    }

    // 3. Empty database with previously indexed media (Reinstall recovery)
    @Test
    fun test3_emptyDatabaseWithPreviouslyIndexedMedia() = runBlocking {
        // Given a reinstalled app where 2,000 photos were scanned as 'NONE' before baseline initialization
        val unverifiedPhotos = (1..20).map { i ->
            Photo(
                localId = "reinstall_$i",
                pathUri = "content://media/external/images/media/$i",
                photoType = "jpg",
                uploadStatus = Photo.STATUS_NONE
            )
        }
        database.photoDao().insertPhotos(*unverifiedPhotos.toTypedArray())
        assertEquals(20, database.photoDao().getUnverifiedNoneCount())

        // When checkAndInitializeBaseline runs
        val state = InstallRecoveryManager.checkAndInitializeBaseline(context, database)

        // Then all unverified photos are moved to HOLDING and baseline is completed
        assertEquals(InstallRecoveryManager.BaselineState.FRESH_BASELINE_ESTABLISHED, state)
        assertEquals(0, database.photoDao().getUnverifiedNoneCount())
        assertEquals(20, database.photoDao().getHoldingCount())
        assertTrue(InstallRecoveryManager.isBaselineCompleted(database))
        assertTrue("Pending uploads must be 0", database.photoDao().getAllPendingUpload().isEmpty())
    }

    // 4. New photo discovered after initial baseline
    @Test
    fun test4_newPhotoDiscoveredAfterInitialBaseline() = runBlocking {
        // Given initial baseline is already completed
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 100)
        assertTrue(InstallRecoveryManager.isBaselineCompleted(database))

        // When a new photo is captured after baseline
        val newPhotoItem = ScannedMediaItem(
            id = "new_photo_1",
            contentUri = "content://media/external/images/media/new_1",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 4096L,
            dateAdded = System.currentTimeMillis() / 1000L,
            dateModified = System.currentTimeMillis() / 1000L,
            dateTaken = System.currentTimeMillis(),
            mediaType = "IMAGE",
            width = 4000,
            height = 3000,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "test_device"
        )
        val defaultStatus = if (!InstallRecoveryManager.isBaselineCompleted(database)) Photo.STATUS_HOLDING else Photo.STATUS_NONE
        assertEquals(Photo.STATUS_NONE, defaultStatus)

        val newPhoto = newPhotoItem.toPhoto(defaultUploadStatus = defaultStatus)
        database.photoDao().insertPhotos(newPhoto)

        // Then new photo is marked NONE and enters pending upload
        val pending = database.photoDao().getAllPendingUpload()
        assertEquals(1, pending.size)
        assertEquals("new_photo_1", pending.first().localId)
        assertEquals(Photo.STATUS_NONE, pending.first().uploadStatus)
    }

    // 5. New video discovered after initial baseline
    @Test
    fun test5_newVideoDiscoveredAfterInitialBaseline() = runBlocking {
        // Given initial baseline is already completed
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 100)

        // When a new video is recorded after baseline
        val newVideoItem = ScannedMediaItem(
            id = "new_video_1",
            contentUri = "content://media/external/video/media/new_v1",
            mimeType = "video/mp4",
            photoType = "mp4",
            size = 15_000_000L,
            dateAdded = System.currentTimeMillis() / 1000L,
            dateModified = System.currentTimeMillis() / 1000L,
            dateTaken = System.currentTimeMillis(),
            mediaType = "VIDEO",
            width = 1920,
            height = 1080,
            duration = 120_000L,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "test_device"
        )
        val defaultStatus = if (!InstallRecoveryManager.isBaselineCompleted(database)) Photo.STATUS_HOLDING else Photo.STATUS_NONE
        val newVideo = newVideoItem.toPhoto(defaultUploadStatus = defaultStatus)
        database.photoDao().insertPhotos(newVideo)

        // Then new video is marked NONE and enters pending upload
        val pending = database.photoDao().getAllPendingUpload()
        assertEquals(1, pending.size)
        assertEquals("new_video_1", pending.first().localId)
        assertEquals("VIDEO", pending.first().mediaType)
    }

    // 6. Existing media not automatically enqueued during protected initial indexing
    @Test
    fun test6_existingMediaNotAutomaticallyEnqueuedDuringProtectedInitialIndexing() = runBlocking {
        // Given an existing media in HOLDING
        val holdingPhoto = Photo(
            localId = "hold_1",
            pathUri = "content://media/external/images/media/hold_1",
            photoType = "jpg",
            uploadStatus = Photo.STATUS_HOLDING
        )
        database.photoDao().insertPhotos(holdingPhoto)

        // When automatic backup attempts to enqueue it
        val enqueued = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(holdingPhoto),
            workerType = "periodic",
            database = database
        )

        // Then it is NOT enqueued into upload_queue
        assertEquals(0, enqueued)
        assertEquals(0, database.uploadQueueDao().getAll().size)
    }

    // 7. Explicit manual backup of an existing holding image
    @Test
    fun test7_explicitManualBackupOfExistingHoldingImage() = runBlocking {
        // Given an existing media in HOLDING
        val holdingPhoto = Photo(
            localId = "manual_target_1",
            pathUri = "content://media/external/images/media/manual_1",
            photoType = "jpg",
            uploadStatus = Photo.STATUS_HOLDING
        )
        database.photoDao().insertPhotos(holdingPhoto)

        // When the user explicitly requests manual backup
        val enqueued = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(holdingPhoto),
            workerType = "manual_backup",
            database = database
        )

        // Then it IS enqueued and its status is updated to NONE for processing
        assertEquals(1, enqueued)
        val queueItem = database.uploadQueueDao().getByLocalId("manual_target_1")
        assertNotNull(queueItem)
        assertEquals("PENDING", queueItem!!.status)
        assertEquals("manual_backup", queueItem.workerType)

        val updatedPhoto = database.photoDao().getPhotoByLocalId("manual_target_1")
        assertEquals(Photo.STATUS_NONE, updatedPhoto!!.uploadStatus)
    }

    // 8. Media with recoverable remote references
    @Test
    fun test8_mediaWithRecoverableRemoteReferences() = runBlocking {
        // Given a holding photo on the device with a known content hash
        val contentHash = "sha256_known_recovery_hash"
        val holdingPhoto = Photo(
            localId = "local_match_1",
            pathUri = "content://media/external/images/media/match_1",
            photoType = "jpg",
            contentHash = contentHash,
            uploadStatus = Photo.STATUS_HOLDING
        )
        database.photoDao().insertPhotos(holdingPhoto)

        // And a RemotePhoto record recovered from backup or catalog
        val remoteRecord = RemotePhoto(
            remoteId = "tg_recovered_file_42",
            photoType = "jpg",
            fileName = "match_1.jpg",
            contentHash = contentHash,
            uploadedAt = 12345678L,
            status = "ACTIVE"
        )
        database.remotePhotoDao().insertAll(remoteRecord)

        // When reconciliation runs
        val matched = InstallRecoveryManager.reconcileLocalPhotosWithRemoteHistory(context, database)

        // Then local photo is reconciled with remoteId and marked DONE
        assertEquals(1, matched)
        val reconciled = database.photoDao().getPhotoByLocalId("local_match_1")
        assertNotNull(reconciled)
        assertEquals("tg_recovered_file_42", reconciled!!.remoteId)
        assertEquals(Photo.STATUS_DONE, reconciled.uploadStatus)
        assertEquals(12345678L, reconciled.lastUploadAttempt)
    }

    // 9. Media with unknown historical backup status
    @Test
    fun test9_mediaWithUnknownHistoricalBackupStatus() = runBlocking {
        // Given a holding photo that has NO remote evidence in remote_photos
        val unknownPhoto = Photo(
            localId = "unknown_status_1",
            pathUri = "content://media/external/images/media/unknown_1",
            photoType = "jpg",
            contentHash = "hash_without_remote",
            uploadStatus = Photo.STATUS_HOLDING
        )
        database.photoDao().insertPhotos(unknownPhoto)

        // When reconciliation runs
        val matched = InstallRecoveryManager.reconcileLocalPhotosWithRemoteHistory(context, database)

        // Then it remains safely in HOLDING without fake remote IDs
        assertEquals(0, matched)
        val preserved = database.photoDao().getPhotoByLocalId("unknown_status_1")
        assertNotNull(preserved)
        assertEquals(Photo.STATUS_HOLDING, preserved!!.uploadStatus)
        assertNull(preserved.remoteId)
    }

    // 10. SAF metadata export/import roundtrip
    @Test
    fun test10_safMetadataExportImportRoundtrip() = runBlocking {
        val mapper = ObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        // Given a BackupFile with ExportedMediaRecord
        val record = ExportedMediaRecord(
            originalMediaId = "export_1",
            originalUri = "content://media/external/images/media/1",
            displayName = "photo_test.jpg",
            fileSize = 1024L,
            mediaType = "IMAGE",
            modificationTimestamp = 9999L,
            contentSha256 = "hash_exported_sha256",
            telegramFileId = "tg_export_id_100",
            telegramMessageId = 555L,
            destinationChannelId = -100123456789L,
            botIdentityId = "gallery_sync_bot",
            backupCompletionState = "ACTIVE",
            backupTimestamp = 8888L
        )

        val backup = BackupFile(
            version = 3,
            deviceId = "dev_origin",
            deviceName = "Origin Device",
            createdAt = 10000L,
            channelId = -100123456789L,
            botUsername = "gallery_sync_bot",
            photos = emptyList(),
            remotePhotos = listOf(
                RemotePhoto(
                    remoteId = "tg_export_id_100",
                    photoType = "jpg",
                    fileName = "photo_test.jpg",
                    fileSize = 1024L,
                    uploadedAt = 8888L,
                    messageId = 555L,
                    localId = "export_1",
                    contentHash = "hash_exported_sha256",
                    status = "ACTIVE"
                )
            ),
            mediaRecords = listOf(record)
        )

        // When serialized and deserialized (JSON roundtrip)
        val jsonBytes = mapper.writeValueAsBytes(backup)
        val deserialized = mapper.readValue(jsonBytes, BackupFile::class.java)

        // Then metadata fields are preserved intact
        assertEquals(3, deserialized.version)
        assertEquals(-100123456789L, deserialized.channelId)
        assertEquals("gallery_sync_bot", deserialized.botUsername)
        assertEquals(1, deserialized.mediaRecords.size)

        val rec = deserialized.mediaRecords.first()
        assertEquals("hash_exported_sha256", rec.contentSha256)
        assertEquals("tg_export_id_100", rec.telegramFileId)
        assertEquals(555L, rec.telegramMessageId)
        assertEquals(-100123456789L, rec.destinationChannelId)
        assertEquals("gallery_sync_bot", rec.botIdentityId)
    }

    // 11. Existing queue records preserved
    @Test
    fun test11_existingQueueRecordsPreserved() = runBlocking {
        // Given pre-existing queue items
        val queueItem = UploadQueue(
            localId = "queue_keep_1",
            pathUri = "content://media/external/images/media/q1",
            status = "RETRY_WAIT",
            workerType = "periodic",
            errorMessage = "Telegram 429",
            retryCount = 2,
            scheduledRetryAt = 99999L
        )
        database.uploadQueueDao().insert(queueItem)

        // When baseline initialization executes
        InstallRecoveryManager.checkAndInitializeBaseline(context, database)

        // Then the queue record remains intact
        val fetched = database.uploadQueueDao().getByLocalId("queue_keep_1")
        assertNotNull(fetched)
        assertEquals("RETRY_WAIT", fetched!!.status)
        assertEquals(2, fetched.retryCount)
        assertEquals(99999L, fetched.scheduledRetryAt)
    }

    // 12. Existing photo upload regression
    @Test
    fun test12_existingPhotoUploadRegression() = runBlocking {
        val photo = Photo(
            localId = "regress_photo",
            pathUri = "content://media/external/images/media/rp",
            photoType = "jpg",
            mediaType = "IMAGE",
            uploadStatus = Photo.STATUS_NONE
        )
        database.photoDao().insertPhotos(photo)

        val enqueued = UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), workerType = "periodic", database = database)
        assertEquals(1, enqueued)

        val queueItem = database.uploadQueueDao().getByLocalId("regress_photo")
        assertNotNull(queueItem)

        // Simulate successful upload completion
        UploadQueueRepository.recordSuccess(queueItem!!.id, "regress_photo", "tg_photo_done_1", database = database)

        val updatedPhoto = database.photoDao().getPhotoByLocalId("regress_photo")
        assertEquals(Photo.STATUS_DONE, updatedPhoto!!.uploadStatus)
        assertEquals("tg_photo_done_1", updatedPhoto.remoteId)
    }

    // 13. Existing normal-video upload regression
    @Test
    fun test13_existingNormalVideoUploadRegression() = runBlocking {
        val video = Photo(
            localId = "regress_video",
            pathUri = "content://media/external/video/media/rv",
            photoType = "mp4",
            mediaType = "VIDEO",
            duration = 30_000L,
            uploadStatus = Photo.STATUS_NONE
        )
        database.photoDao().insertPhotos(video)

        val enqueued = UploadQueueRepository.enqueueMediaForBackup(context, listOf(video), workerType = "periodic", database = database)
        assertEquals(1, enqueued)

        val queueItem = database.uploadQueueDao().getByLocalId("regress_video")
        assertNotNull(queueItem)
        assertEquals("VIDEO", queueItem!!.mediaType)

        // Simulate successful upload completion
        UploadQueueRepository.recordSuccess(queueItem.id, "regress_video", "tg_video_done_1", database = database)

        val updatedVideo = database.photoDao().getPhotoByLocalId("regress_video")
        assertEquals(Photo.STATUS_DONE, updatedVideo!!.uploadStatus)
        assertEquals("tg_video_done_1", updatedVideo.remoteId)
    }

    // 14. Existing chunked upload regression
    @Test
    fun test14_existingChunkedUploadRegression() = runBlocking {
        val backupDao = database.chunkedBackupDao()
        val chunkDao = database.videoChunkDao()

        val backup = ChunkedBackup(
            backupId = "backup_chunk_1",
            localId = "vid_chunk_local",
            pathUri = "content://media/external/video/media/100",
            fileName = "large_video.mp4",
            fileSize = 120_000_000L,
            mimeType = "video/mp4",
            totalChunks = 6,
            chunkSize = 20_000_000L,
            channelId = -100123456789L,
            botId = 123456L,
            status = ChunkedBackupStatus.UPLOADING
        )
        backupDao.insert(backup)

        val chunk1 = VideoChunk(
            backupId = "backup_chunk_1",
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 20_000_000L,
            chunkSize = 20_000_000L,
            chunkSha256 = "c0_hash",
            status = VideoChunkStatus.DONE,
            telegramFileId = "tg_chunk_0"
        )
        chunkDao.insert(chunk1)

        val retrievedBackup = backupDao.getById("backup_chunk_1")
        assertNotNull(retrievedBackup)
        assertEquals(ChunkedBackupStatus.UPLOADING, retrievedBackup!!.status)

        val retrievedChunk = chunkDao.getByBackupIdAndIndex("backup_chunk_1", 0)
        assertNotNull(retrievedChunk)
        assertEquals(VideoChunkStatus.DONE, retrievedChunk!!.status)
        assertEquals("tg_chunk_0", retrievedChunk.telegramFileId)
    }

    // 15. Fresh database before initial MediaStore scan returns PENDING_INITIAL_SCAN without marking baseline done
    @Test
    fun test15_freshDatabaseAwaitingInitialScanReturnsPending() = runBlocking {
        // Given a fresh database with 0 photos and 0 remote photos
        assertEquals(0, database.photoDao().getCount())
        assertEquals(0, database.remotePhotoDao().getCount())
        assertFalse(InstallRecoveryManager.isBaselineCompleted(database))

        // When checkAndInitializeBaseline is called
        val state = InstallRecoveryManager.checkAndInitializeBaseline(context, database)

        // Then it returns PENDING_INITIAL_SCAN and does NOT mark baseline completed prematurely
        assertEquals(InstallRecoveryManager.BaselineState.PENDING_INITIAL_SCAN, state)
        assertFalse("Baseline must not be marked completed when database has zero photos", InstallRecoveryManager.isBaselineCompleted(database))
    }

    // 16. Late-discovered older media is placed in HOLDING while genuinely new media is placed in NONE
    @Test
    fun test16_timestampBoundaryProtectionForLateDiscoveredMedia() = runBlocking {
        // Given an initial baseline established at timestamp T_baseline = 1_000_000_000L ms
        val baselineTimestampMs = 1_000_000_000L
        InstallRecoveryManager.markBaselineCompleted(database, mediaCount = 50, timestamp = baselineTimestampMs)
        assertTrue(InstallRecoveryManager.isBaselineCompleted(database))
        assertEquals(baselineTimestampMs, InstallRecoveryManager.getBaselineTimestamp(database))

        // When an older photo (dateAdded/dateModified before baseline) is discovered late
        val olderItem = ScannedMediaItem(
            id = "older_late_1",
            contentUri = "content://media/external/images/media/older_1",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 2048L,
            dateAdded = 900_000L, // 900,000s = 900,000,000 ms <= baselineTimestampMs
            dateModified = 900_000L,
            dateTaken = 900_000_000L,
            mediaType = "IMAGE",
            width = 1920,
            height = 1080,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "test_device"
        )
        val itemTimestampMs = maxOf(olderItem.dateAdded, olderItem.dateModified) * 1000L
        val isBaselineDone = InstallRecoveryManager.isBaselineCompleted(database)
        val olderStatus = if (!isBaselineDone || (baselineTimestampMs > 0L && itemTimestampMs <= baselineTimestampMs)) {
            Photo.STATUS_HOLDING
        } else {
            Photo.STATUS_NONE
        }
        val olderPhoto = olderItem.toPhoto(defaultUploadStatus = olderStatus)
        database.photoDao().insertPhotos(olderPhoto)

        // Then the older late-discovered photo is placed in HOLDING
        val savedOlder = database.photoDao().getPhotoByLocalId("older_late_1")
        assertNotNull(savedOlder)
        assertEquals(Photo.STATUS_HOLDING, savedOlder!!.uploadStatus)

        // When a genuinely new photo (dateAdded after baseline) is captured
        val newerItem = ScannedMediaItem(
            id = "newer_photo_1",
            contentUri = "content://media/external/images/media/newer_1",
            mimeType = "image/jpeg",
            photoType = "jpg",
            size = 4096L,
            dateAdded = 1_100_000L, // 1,100,000s = 1,100,000,000 ms > baselineTimestampMs
            dateModified = 1_100_000L,
            dateTaken = 1_100_000_000L,
            mediaType = "IMAGE",
            width = 4000,
            height = 3000,
            duration = null,
            bucketId = 1L,
            bucketName = "Camera",
            deviceId = "test_device"
        )
        val newItemTimestampMs = maxOf(newerItem.dateAdded, newerItem.dateModified) * 1000L
        val newerStatus = if (!isBaselineDone || (baselineTimestampMs > 0L && newItemTimestampMs <= baselineTimestampMs)) {
            Photo.STATUS_HOLDING
        } else {
            Photo.STATUS_NONE
        }
        val newerPhoto = newerItem.toPhoto(defaultUploadStatus = newerStatus)
        database.photoDao().insertPhotos(newerPhoto)

        // Then the newer photo is marked NONE and enters pending upload
        val savedNewer = database.photoDao().getPhotoByLocalId("newer_photo_1")
        assertNotNull(savedNewer)
        assertEquals(Photo.STATUS_NONE, savedNewer!!.uploadStatus)

        val pending = database.photoDao().getAllPendingUpload()
        assertEquals(1, pending.size)
        assertEquals("newer_photo_1", pending.first().localId)
    }
}
