package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.upload.TelegramUploadDispatcher.UploadError
import com.gallerysync.app.data.upload.UploadQueueRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadQueueRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    fun test1_duplicateEnqueuePrevention() = runBlocking {
        val photo = Photo(
            localId = "media_1",
            pathUri = "content://media/external/images/media/1",
            photoType = "jpg",
            mediaType = "IMAGE"
        )
        database.photoDao().insertPhotos(photo)

        // First enqueue
        val enqueued1 = UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)
        assertEquals(1, enqueued1)
        assertEquals(1, database.uploadQueueDao().getAll().size)

        // Second enqueue of same photo
        val enqueued2 = UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)
        assertEquals(0, enqueued2)
        assertEquals("Queue must still contain exactly 1 entry", 1, database.uploadQueueDao().getAll().size)
    }

    @Test
    fun test2_concurrentEnqueueOperations() = runBlocking {
        val photos = (1..10).map { i ->
            Photo(
                localId = "media_conc_$i",
                pathUri = "content://media/external/images/media/$i",
                photoType = "jpg",
                mediaType = "IMAGE"
            )
        }
        database.photoDao().insertPhotos(*photos.toTypedArray())

        // Launch concurrent enqueue calls for overlapping sets
        coroutineScope {
            val task1 = async { UploadQueueRepository.enqueueMediaForBackup(context, photos, database = database) }
            val task2 = async { UploadQueueRepository.enqueueMediaForBackup(context, photos, database = database) }
            val task3 = async { UploadQueueRepository.enqueueMediaForBackup(context, photos.take(5), database = database) }
            awaitAll(task1, task2, task3)
        }

        val allQueue = database.uploadQueueDao().getAll()
        assertEquals("Queue must contain exactly 10 distinct records without collision errors", 10, allQueue.size)
    }

    @Test
    fun test3_atomicClaiming() = runBlocking {
        val photo = Photo(
            localId = "media_claim_1",
            pathUri = "content://media/external/images/media/100",
            photoType = "jpg",
            mediaType = "IMAGE"
        )
        database.photoDao().insertPhotos(photo)
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)

        val workerToken = UUID.randomUUID().toString()
        val claimed = UploadQueueRepository.claimNextItem(workerToken, database = database)

        assertNotNull(claimed)
        assertEquals("media_claim_1", claimed!!.localId)
        assertEquals("IN_PROGRESS", claimed.status)
        assertEquals(workerToken, claimed.workManagerId)
        assertNotNull(claimed.startedAt)
    }

    @Test
    fun test4_multipleWorkersCannotClaimSameItem() = runBlocking {
        val photo = Photo(
            localId = "media_single_item",
            pathUri = "content://media/external/images/media/200",
            photoType = "jpg",
            mediaType = "IMAGE"
        )
        database.photoDao().insertPhotos(photo)
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)

        val workerA = "worker_token_A"
        val workerB = "worker_token_B"

        val claimedA = UploadQueueRepository.claimNextItem(workerA, database = database)
        assertNotNull("Worker A claims the item", claimedA)

        val claimedB = UploadQueueRepository.claimNextItem(workerB, database = database)
        assertNull("Worker B must receive null since no items remain pending", claimedB)
    }

    @Test
    fun test5_staleInProgressRecovery() = runBlocking {
        val pastTime = System.currentTimeMillis() - (15 * 60 * 1000L) // 15 mins ago
        val staleItem = UploadQueue(
            localId = "stale_media",
            pathUri = "content://media/external/images/media/300",
            status = "IN_PROGRESS",
            workManagerId = "dead_worker",
            startedAt = pastTime,
            workerType = "periodic"
        )
        val id = database.uploadQueueDao().insert(staleItem)

        val recoveredCount = UploadQueueRepository.recoverStaleUploads(
            staleThresholdMs = 10 * 60 * 1000L,
            database = database
        )

        assertEquals("Exactly 1 stale item should be recovered", 1, recoveredCount)
        val updated = database.uploadQueueDao().getById(id)
        assertNotNull(updated)
        assertEquals("PENDING", updated!!.status)
        assertNull("Worker token must be cleared on recovery", updated.workManagerId)
    }

    @Test
    fun test6_activeWorkerPreservation() = runBlocking {
        val recentTime = System.currentTimeMillis() - (2 * 60 * 1000L) // only 2 mins ago
        val activeItem = UploadQueue(
            localId = "active_media",
            pathUri = "content://media/external/images/media/400",
            status = "IN_PROGRESS",
            workManagerId = "active_worker",
            startedAt = recentTime,
            workerType = "periodic"
        )
        val id = database.uploadQueueDao().insert(activeItem)

        val recoveredCount = UploadQueueRepository.recoverStaleUploads(
            staleThresholdMs = 10 * 60 * 1000L,
            database = database
        )

        assertEquals("Active item must NOT be recovered", 0, recoveredCount)
        val current = database.uploadQueueDao().getById(id)
        assertNotNull(current)
        assertEquals("IN_PROGRESS", current!!.status)
        assertEquals("active_worker", current.workManagerId)
    }

    @Test
    fun test7_exponentialBackoffRetryScheduling() = runBlocking {
        // Test backoff calculation logic
        assertEquals(30_000L, UploadQueueRepository.calculateBackoffMs(1))
        assertEquals(60_000L, UploadQueueRepository.calculateBackoffMs(2))
        assertEquals(120_000L, UploadQueueRepository.calculateBackoffMs(3))
        assertEquals(30 * 60 * 1000L, UploadQueueRepository.calculateBackoffMs(10)) // capped at 30 min

        val queueItem = UploadQueue(
            localId = "retry_media",
            pathUri = "content://media/external/images/media/500",
            status = "IN_PROGRESS",
            retryCount = 0,
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(Photo(localId = "retry_media", pathUri = queueItem.pathUri, photoType = "jpg"))

        // Record first network failure
        val beforeFail = System.currentTimeMillis()
        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "retry_media",
            error = UploadError.NetworkUnavailable("Connection timed out"),
            database = database
        )

        val itemAfterFail1 = database.uploadQueueDao().getById(qid)!!
        assertEquals("RETRY_WAIT", itemAfterFail1.status)
        assertEquals(1, itemAfterFail1.retryCount)
        assertNotNull(itemAfterFail1.scheduledRetryAt)
        assertTrue(itemAfterFail1.scheduledRetryAt!! >= beforeFail + 15_000L)
    }

    @Test
    fun test8_telegram429RespectsRetryAfter() = runBlocking {
        val queueItem = UploadQueue(
            localId = "rate_limited_media",
            pathUri = "content://media/external/images/media/600",
            status = "IN_PROGRESS",
            retryCount = 1,
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(Photo(localId = "rate_limited_media", pathUri = queueItem.pathUri, photoType = "jpg"))

        val beforeTime = System.currentTimeMillis()
        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "rate_limited_media",
            error = UploadError.RateLimited(retryAfterSeconds = 45),
            database = database
        )

        val updated = database.uploadQueueDao().getById(qid)!!
        assertEquals("RETRY_WAIT", updated.status)
        assertEquals("retryCount must NOT increment for 429 rate limit", 1, updated.retryCount)
        assertNotNull(updated.scheduledRetryAt)
        assertTrue(updated.scheduledRetryAt!! >= beforeTime + 45_000L)
    }

    @Test
    fun test9_networkRecovery() = runBlocking {
        // Item with scheduledRetryAt in the future: cannot be claimed
        val futureItem = UploadQueue(
            localId = "future_retry",
            pathUri = "content://media/external/images/media/701",
            status = "RETRY_WAIT",
            scheduledRetryAt = System.currentTimeMillis() + 60_000L,
            workerType = "periodic"
        )
        database.uploadQueueDao().insert(futureItem)

        // Item with scheduledRetryAt in the past: ready to be claimed
        val pastItem = UploadQueue(
            localId = "past_retry",
            pathUri = "content://media/external/images/media/702",
            status = "RETRY_WAIT",
            scheduledRetryAt = System.currentTimeMillis() - 1_000L,
            workerType = "periodic"
        )
        database.uploadQueueDao().insert(pastItem)

        val claimed = UploadQueueRepository.claimNextItem("worker_1", database = database)
        assertNotNull(claimed)
        assertEquals("past_retry", claimed!!.localId)
        assertEquals("IN_PROGRESS", claimed.status)
    }

    @Test
    fun test10_permanentErrorsNeverAutoRetried() = runBlocking {
        val queueItem = UploadQueue(
            localId = "invalid_cred_media",
            pathUri = "content://media/external/images/media/800",
            status = "IN_PROGRESS",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(Photo(localId = "invalid_cred_media", pathUri = queueItem.pathUri, photoType = "jpg"))

        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "invalid_cred_media",
            error = UploadError.InvalidCredentials(401),
            database = database
        )

        val queueRecord = database.uploadQueueDao().getById(qid)!!
        assertEquals("FAILED", queueRecord.status)
        assertNull("Permanent error must have null scheduledRetryAt", queueRecord.scheduledRetryAt)

        val photoRecord = database.photoDao().getPhotoByLocalId("invalid_cred_media")!!
        assertEquals("FAILED", photoRecord.uploadStatus)

        // Verify that subsequent claimNextItem never picks up this FAILED record
        val claimed = UploadQueueRepository.claimNextItem("worker_x", database = database)
        assertNull("Failed items without scheduledRetryAt are never auto-claimed", claimed)
    }

    @Test
    fun test11_cloudHashDuplicateDetection() = runBlocking {
        val existingHash = "sha256_cloud_match_123"
        // Seed remote photo table with an already uploaded photo having this hash
        val remotePhoto = RemotePhoto(
            remoteId = "tg_file_remote_999",
            photoType = "jpg",
            uploadedAt = System.currentTimeMillis(),
            contentHash = existingHash
        )
        database.remotePhotoDao().insertAll(remotePhoto)

        val localPhoto = Photo(
            localId = "local_dup_photo",
            pathUri = "content://media/external/images/media/900",
            photoType = "jpg",
            contentHash = existingHash
        )
        database.photoDao().insertPhotos(localPhoto)

        // Enqueue media for backup - should automatically detect cloud duplicate
        val enqueued = UploadQueueRepository.enqueueMediaForBackup(context, listOf(localPhoto), database = database)
        assertEquals(0, enqueued)

        val updatedPhoto = database.photoDao().getPhotoByLocalId("local_dup_photo")!!
        assertEquals("DONE", updatedPhoto.uploadStatus)
        assertEquals("tg_file_remote_999", updatedPhoto.remoteId)

        val queueItem = database.uploadQueueDao().getByLocalId("local_dup_photo")
        assertNull("Cloud duplicate photo requires no queue item", queueItem)
    }

    @Test
    fun test12_statusConsistencyBetweenQueueAndPhoto() = runBlocking {
        val photo = Photo(
            localId = "consistency_photo",
            pathUri = "content://media/external/images/media/1000",
            photoType = "jpg",
            uploadStatus = "NONE"
        )
        database.photoDao().insertPhotos(photo)

        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)

        val enqueuedPhoto = database.photoDao().getPhotoByLocalId("consistency_photo")!!
        assertEquals("NONE", enqueuedPhoto.uploadStatus)

        val queueItem = database.uploadQueueDao().getByLocalId("consistency_photo")!!
        assertEquals("PENDING", queueItem.status)

        // Mark success
        UploadQueueRepository.recordSuccess(
            queueId = queueItem.id,
            localId = "consistency_photo",
            remoteId = "remote_success_123",
            messageId = 42L,
            database = database
        )

        val donePhoto = database.photoDao().getPhotoByLocalId("consistency_photo")!!
        assertEquals("DONE", donePhoto.uploadStatus)
        assertEquals("remote_success_123", donePhoto.remoteId)

        val doneQueue = database.uploadQueueDao().getById(queueItem.id)!!
        assertEquals("DONE", doneQueue.status)
        assertNotNull(doneQueue.completedAt)
    }

    @Test
    fun test13_userManualRetry() = runBlocking {
        val failedQueueItem = UploadQueue(
            localId = "failed_item_retry",
            pathUri = "content://media/external/images/media/1100",
            status = "FAILED",
            retryCount = 5,
            errorMessage = "Fatal timeout",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(failedQueueItem)
        database.photoDao().insertPhotos(
            Photo(localId = "failed_item_retry", pathUri = failedQueueItem.pathUri, photoType = "jpg", uploadStatus = "FAILED")
        )

        UploadQueueRepository.retryQueueItem(qid, database = database)

        val retriedQueue = database.uploadQueueDao().getById(qid)!!
        assertEquals("PENDING", retriedQueue.status)
        assertEquals(0, retriedQueue.retryCount)
        assertNull(retriedQueue.errorMessage)
        assertNull(retriedQueue.scheduledRetryAt)

        val retriedPhoto = database.photoDao().getPhotoByLocalId("failed_item_retry")!!
        assertEquals("NONE", retriedPhoto.uploadStatus)
    }

    @Test
    fun test14_userCancellation() = runBlocking {
        val queueItem = UploadQueue(
            localId = "cancel_item",
            pathUri = "content://media/external/images/media/1200",
            status = "IN_PROGRESS",
            workManagerId = "active_token",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(
            Photo(localId = "cancel_item", pathUri = queueItem.pathUri, photoType = "jpg", uploadStatus = "PENDING")
        )

        UploadQueueRepository.cancelQueueItem(qid, database = database)

        val cancelledQueue = database.uploadQueueDao().getById(qid)!!
        assertEquals("CANCELLED", cancelledQueue.status)
        assertNull(cancelledQueue.workManagerId)

        val cancelledPhoto = database.photoDao().getPhotoByLocalId("cancel_item")!!
        assertEquals("NONE", cancelledPhoto.uploadStatus)
    }

    @Test
    fun test15_retentionOfOversizedMedia() = runBlocking {
        val queueItem = UploadQueue(
            localId = "oversized_item",
            pathUri = "content://media/external/video/media/1300",
            status = "IN_PROGRESS",
            mediaType = "VIDEO",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(
            Photo(localId = "oversized_item", pathUri = queueItem.pathUri, photoType = "mp4", mediaType = "VIDEO")
        )

        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "oversized_item",
            error = UploadError.FileExceedsLimit(2500L * 1024 * 1024),
            database = database
        )

        val record = database.uploadQueueDao().getById(qid)!!
        assertEquals("EXCEEDS_LIMIT", record.status)
        assertNull("Oversized media must never be scheduled for auto-retry", record.scheduledRetryAt)

        val photo = database.photoDao().getPhotoByLocalId("oversized_item")!!
        assertEquals("EXCEEDS_LIMIT", photo.uploadStatus)
    }

    @Test
    fun test16_missingSourceRetention() = runBlocking {
        val queueItem = UploadQueue(
            localId = "missing_item",
            pathUri = "content://media/external/images/media/1400",
            status = "IN_PROGRESS",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(queueItem)
        database.photoDao().insertPhotos(
            Photo(localId = "missing_item", pathUri = queueItem.pathUri, photoType = "jpg")
        )

        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "missing_item",
            error = UploadError.SourceMissing("File deleted from device storage"),
            database = database
        )

        val record = database.uploadQueueDao().getById(qid)!!
        assertEquals("SOURCE_MISSING", record.status)
        assertNull("Missing source media must never be scheduled for auto-retry", record.scheduledRetryAt)

        val photo = database.photoDao().getPhotoByLocalId("missing_item")!!
        assertEquals("SOURCE_MISSING", photo.uploadStatus)
    }

    @Test
    fun test17_completedUploadsNotReEnqueued() = runBlocking {
        val completedPhoto = Photo(
            localId = "completed_media",
            pathUri = "content://media/external/images/media/1500",
            photoType = "jpg",
            uploadStatus = "DONE",
            remoteId = "tg_completed_123"
        )
        database.photoDao().insertPhotos(completedPhoto)

        val count = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(completedPhoto),
            database = database
        )

        assertEquals("Completed media should not be enqueued", 0, count)
        val queueItem = database.uploadQueueDao().getByLocalId("completed_media")
        assertNull("No queue record should be created for already completed photo", queueItem)
    }

    @Test
    fun test18_releaseWorkerClaimsOnStop() = runBlocking {
        val workerToken = "worker_to_be_stopped"
        val item1 = UploadQueue(
            localId = "stopped_1",
            pathUri = "content://media/external/images/media/1601",
            status = "IN_PROGRESS",
            workManagerId = workerToken,
            workerType = "periodic"
        )
        val item2 = UploadQueue(
            localId = "stopped_2",
            pathUri = "content://media/external/images/media/1602",
            status = "IN_PROGRESS",
            workManagerId = "other_worker",
            workerType = "periodic"
        )
        val id1 = database.uploadQueueDao().insert(item1)
        val id2 = database.uploadQueueDao().insert(item2)

        UploadQueueRepository.releaseWorkerClaims(workerToken, database = database)

        val updated1 = database.uploadQueueDao().getById(id1)!!
        assertEquals("PENDING", updated1.status)
        assertNull(updated1.workManagerId)

        val updated2 = database.uploadQueueDao().getById(id2)!!
        assertEquals("IN_PROGRESS", updated2.status)
        assertEquals("other_worker", updated2.workManagerId)
    }

    // --- Phase 6.1 Audit & Hardening Tests ---

    @Test
    fun testPhase6_1_activeUploadExceedingTenMinutesNotReclaimed() = runBlocking {
        val startTime = 1000000L
        val item = UploadQueue(
            localId = "long_running_video",
            pathUri = "content://media/external/video/media/1",
            status = "IN_PROGRESS",
            workManagerId = "worker_active",
            startedAt = startTime,
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)

        // 12 minutes later, worker renews its lease via heartbeat
        val renewalTime = startTime + (12 * 60 * 1000L)
        val renewed = UploadQueueRepository.renewLease(qid, "worker_active", now = renewalTime, database = database)
        assertTrue("Worker lease renewal must succeed", renewed)

        // Stale upload recovery runs at minute 13 (with 10-minute threshold)
        val checkTime = startTime + (13 * 60 * 1000L)
        val recovered = UploadQueueRepository.recoverStaleUploads(
            staleThresholdMs = 10 * 60 * 1000L,
            now = checkTime,
            database = database
        )

        assertEquals("Active upload with renewed lease must NOT be reclaimed", 0, recovered)
        val current = database.uploadQueueDao().getById(qid)!!
        assertEquals("Status must remain IN_PROGRESS", "IN_PROGRESS", current.status)
        assertEquals("worker_active", current.workManagerId)
        assertEquals(renewalTime, current.startedAt)
    }

    @Test
    fun testPhase6_1_expiredWorkerLeaseIsRecovered() = runBlocking {
        val startTime = 1000000L
        val item = UploadQueue(
            localId = "dead_worker_item",
            pathUri = "content://media/external/images/media/2",
            status = "IN_PROGRESS",
            workManagerId = "worker_crashed",
            startedAt = startTime,
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)

        // No lease renewal occurs. Check at minute 11 (exceeding 10 min threshold)
        val checkTime = startTime + (11 * 60 * 1000L)
        val recovered = UploadQueueRepository.recoverStaleUploads(
            staleThresholdMs = 10 * 60 * 1000L,
            now = checkTime,
            database = database
        )

        assertEquals("Expired worker lease must be recovered", 1, recovered)
        val current = database.uploadQueueDao().getById(qid)!!
        assertEquals("Status must be reset to PENDING", "PENDING", current.status)
        assertNull("Worker token must be cleared", current.workManagerId)
    }

    @Test
    fun testPhase6_1_twoWorkersCannotUploadSameQueueItem() = runBlocking {
        val item = UploadQueue(
            localId = "concurrent_item",
            pathUri = "content://media/external/images/media/3",
            status = "PENDING",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)

        // Worker 1 claims item
        val rows1 = database.uploadQueueDao().claimQueueItem(qid, "worker_1")
        assertEquals(1, rows1)

        // Worker 2 attempts to claim same item
        val rows2 = database.uploadQueueDao().claimQueueItem(qid, "worker_2")
        assertEquals("Second worker claim must be rejected (0 rows updated)", 0, rows2)

        val queueRecord = database.uploadQueueDao().getById(qid)!!
        assertEquals("worker_1", queueRecord.workManagerId)
    }

    @Test
    fun testPhase6_1_instantAndPeriodicTriggersUseSameQueueProcessingMechanism() = runBlocking {
        val periodicPhoto = Photo(
            localId = "periodic_photo_1",
            pathUri = "content://media/external/images/media/4",
            photoType = "jpg"
        )
        val instantPhoto = Photo(
            localId = "instant_photo_1",
            pathUri = "content://media/external/images/media/5",
            photoType = "jpg"
        )
        database.photoDao().insertPhotos(periodicPhoto, instantPhoto)

        // Periodic enqueue
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(periodicPhoto), workerType = "periodic", database = database)
        // Instant enqueue
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(instantPhoto), workerType = "instant", database = database)

        val allQueue = database.uploadQueueDao().getAll()
        assertEquals("Both triggers persist into the same authoritative upload_queue", 2, allQueue.size)

        // Single queue processor claims both sequentially
        val claimed1 = UploadQueueRepository.claimNextItem("queue_processor", database = database)
        assertNotNull(claimed1)
        val claimed2 = UploadQueueRepository.claimNextItem("queue_processor", database = database)
        assertNotNull(claimed2)

        val claimedLocalIds = setOf(claimed1!!.localId, claimed2!!.localId)
        assertTrue(claimedLocalIds.contains("periodic_photo_1"))
        assertTrue(claimedLocalIds.contains("instant_photo_1"))
    }

    @Test
    fun testPhase6_1_maximumRetryCountIsEnforced() = runBlocking {
        val item = UploadQueue(
            localId = "max_retry_item",
            pathUri = "content://media/external/images/media/6",
            status = "IN_PROGRESS",
            retryCount = 5, // 5 retries already exhausted
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)
        database.photoDao().insertPhotos(Photo(localId = "max_retry_item", pathUri = item.pathUri, photoType = "jpg"))

        // 5th failure: hits MAX_RETRIES (5)
        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "max_retry_item",
            error = UploadError.NetworkUnavailable("5th timeout"),
            database = database
        )

        val failedItem = database.uploadQueueDao().getById(qid)!!
        assertEquals("FAILED", failedItem.status)
        assertNull("Terminal failure must not have scheduled retry", failedItem.scheduledRetryAt)
        assertTrue("Error message reflects max retries", failedItem.errorMessage!!.contains("Maximum retries"))

        val photo = database.photoDao().getPhotoByLocalId("max_retry_item")!!
        assertEquals("FAILED", photo.uploadStatus)
    }

    @Test
    fun testPhase6_1_telegram429DoesNotConsumeNormalRetryAttempts() = runBlocking {
        val item = UploadQueue(
            localId = "rate_limit_item",
            pathUri = "content://media/external/images/media/7",
            status = "IN_PROGRESS",
            retryCount = 2,
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)
        database.photoDao().insertPhotos(Photo(localId = "rate_limit_item", pathUri = item.pathUri, photoType = "jpg"))

        val now = 2000000L
        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "rate_limit_item",
            error = UploadError.RateLimited(retryAfterSeconds = 120),
            now = now,
            database = database
        )

        val updated = database.uploadQueueDao().getById(qid)!!
        assertEquals("RETRY_WAIT", updated.status)
        assertEquals("Telegram 429 must NOT increment normal retryCount", 2, updated.retryCount)
        assertEquals(now + 120_000L, updated.scheduledRetryAt)
    }

    @Test
    fun testPhase6_1_scheduledRetryBecomesEligibleAtCorrectTime() = runBlocking {
        val now = 3000000L
        val item = UploadQueue(
            localId = "time_check_item",
            pathUri = "content://media/external/images/media/8",
            status = "RETRY_WAIT",
            scheduledRetryAt = now + 60_000L, // 60 seconds from now
            workerType = "periodic"
        )
        database.uploadQueueDao().insert(item)

        // Attempt claim at T = now (not due yet)
        val notYetEligible = UploadQueueRepository.claimNextItem("worker_time", now = now, database = database)
        assertNull("Item must NOT be claimable before scheduledRetryAt", notYetEligible)

        // Attempt claim at T = now + 60_000L (due now)
        val eligibleNow = UploadQueueRepository.claimNextItem("worker_time", now = now + 60_000L, database = database)
        assertNotNull("Item MUST be claimable when scheduledRetryAt is reached", eligibleNow)
        assertEquals("time_check_item", eligibleNow!!.localId)
        assertEquals("IN_PROGRESS", eligibleNow.status)
    }

    @Test
    fun testPhase6_1_cancellationDoesNotIncorrectlyMarkUploadCompleted() = runBlocking {
        val item = UploadQueue(
            localId = "cancelled_item",
            pathUri = "content://media/external/images/media/9",
            status = "IN_PROGRESS",
            workManagerId = "active_worker",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)
        database.photoDao().insertPhotos(Photo(localId = "cancelled_item", pathUri = item.pathUri, photoType = "jpg", uploadStatus = "PENDING"))

        UploadQueueRepository.cancelQueueItem(qid, database = database)

        val cancelledQueue = database.uploadQueueDao().getById(qid)!!
        assertEquals("CANCELLED", cancelledQueue.status)
        assertFalse("Cancellation must never mark upload completed", cancelledQueue.status == "DONE" || cancelledQueue.status == "COMPLETED")
        assertNull("Completed timestamp must be null", cancelledQueue.completedAt)

        val cancelledPhoto = database.photoDao().getPhotoByLocalId("cancelled_item")!!
        assertEquals("NONE", cancelledPhoto.uploadStatus)
        assertFalse("Photo must not be marked DONE", cancelledPhoto.uploadStatus == "DONE")
    }

    @Test
    fun testPhase6_1_interruptedUploadRecoveryDoesNotCreateDuplicateQueueEntries() = runBlocking {
        val photo = Photo(
            localId = "interrupted_photo",
            pathUri = "content://media/external/images/media/10",
            photoType = "jpg"
        )
        database.photoDao().insertPhotos(photo)

        // 1. Initial enqueue & claim
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)
        val claimed = UploadQueueRepository.claimNextItem("worker_initial", database = database)!!
        assertEquals("IN_PROGRESS", claimed.status)

        // 2. Interruption occurs (worker killed)
        UploadQueueRepository.recoverStaleUploads(
            staleThresholdMs = 0L,
            now = System.currentTimeMillis() + 1000L,
            database = database
        )

        // 3. Rescan / recovery attempts to re-enqueue
        val reEnqueued = UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)
        assertEquals(0, reEnqueued)

        // Verify only 1 queue record exists
        val allQueue = database.uploadQueueDao().getAll()
        val matching = allQueue.filter { it.localId == "interrupted_photo" }
        assertEquals("Must never create duplicate queue entries for recovered item", 1, matching.size)
        assertEquals("PENDING", matching.first().status)
    }
}
