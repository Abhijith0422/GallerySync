package com.gallerysync.app.data.upload

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.upload.TelegramUploadDispatcher.UploadError
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * Authoritative repository managing the persistent Room upload queue.
 *
 * Guarantees that:
 * 1. Room's upload_queue is the single source of truth for all media backups.
 * 2. Media is enqueued and persisted before upload begins.
 * 3. Items are claimed atomically with worker tokens, preventing concurrent worker race conditions.
 * 4. Stale/interrupted uploads are recovered safely across app restarts and crashes.
 * 5. Exponential backoff and Telegram 429 retry_after durations are durably persisted in SQLite.
 * 6. Completed uploads and hash duplicates are never re-uploaded.
 */
object UploadQueueRepository {
    private const val TAG = "UploadQueueRepo"

    const val MAX_RETRIES = 5
    const val DEFAULT_STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Calculates exponential backoff duration: min(15 seconds * 2^retryCount, 30 minutes).
     */
    fun calculateBackoffMs(retryCount: Int): Long {
        val exponent = minOf(retryCount, 7) // 15s * 2^7 = 1920s (~32 min, capped at 30 min)
        val delay = 15_000L * (1L shl exponent)
        return minOf(delay, 30 * 60 * 1000L)
    }

    /**
     * Enqueues a list of media photos for backup in the persistent queue.
     * Prevents duplicate queue entries, preserves existing metadata, and ensures
     * consistency between the `photos` and `upload_queue` tables.
     *
     * @return Number of newly enqueued or reset-to-pending items.
     */
    suspend fun enqueueMediaForBackup(
        context: Context,
        photos: List<Photo>,
        workerType: String = "auto",
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()
        val remotePhotoDao = database.remotePhotoDao()
        val deviceId = Preferences.getOrCreateDeviceId()

        var enqueuedCount = 0

        database.withTransaction {
            for (photo in photos) {
                // 1. Skip if already completed or linked to remote
                if (photo.remoteId != null || photo.uploadStatus == "DONE") {
                    continue
                }

                // 2. Hash-based cloud deduplication check
                val hash = photo.contentHash ?: run {
                    try {
                        ContentHasher.computeHash(context, photo.pathUri.toUri())
                    } catch (_: Exception) {
                        null
                    }
                }
                if (hash != null) {
                    if (photo.contentHash == null) {
                        photoDao.updateContentHash(photo.localId, hash)
                    }
                    val cloudExisting = remotePhotoDao.getByContentHash(hash)
                    if (cloudExisting != null) {
                        Log.d(TAG, "Deduplication: ${photo.localId} already exists in cloud with remoteId=${cloudExisting.remoteId}")
                        photoDao.updateRemoteIdForLocalId(photo.localId, cloudExisting.remoteId)
                        photoDao.updateUploadStatus(photo.localId, "DONE", System.currentTimeMillis())
                        continue
                    }
                }

                // 3. Check existing queue record for this localId
                val existing = uploadQueueDao.getByLocalId(photo.localId)
                if (existing != null) {
                    when (existing.status) {
                        "DONE", "COMPLETED" -> {
                            photoDao.updateUploadStatus(photo.localId, "DONE")
                            continue
                        }
                        "EXCEEDS_LIMIT", "SOURCE_MISSING" -> {
                            photoDao.updateUploadStatus(photo.localId, existing.status)
                            continue
                        }
                        "PENDING", "IN_PROGRESS", "RETRY_WAIT" -> {
                            // Already actively queued
                            continue
                        }
                        "FAILED", "CANCELLED" -> {
                            // User or scheduler re-enqueued a failed/cancelled item
                            uploadQueueDao.insertOrReplace(
                                existing.copy(
                                    status = "PENDING",
                                    workerType = workerType,
                                    errorMessage = null,
                                    retryCount = 0,
                                    scheduledRetryAt = null,
                                    workManagerId = null,
                                    createdAt = System.currentTimeMillis(),
                                    startedAt = null,
                                    completedAt = null
                                )
                            )
                            photoDao.updateUploadStatus(photo.localId, "NONE")
                            enqueuedCount++
                        }
                    }
                } else {
                    // 4. Create new persistent queue entry
                    uploadQueueDao.insert(
                        UploadQueue(
                            localId = photo.localId,
                            pathUri = photo.pathUri,
                            contentHash = hash,
                            status = "PENDING",
                            workerType = workerType,
                            deviceId = deviceId,
                            mediaType = photo.mediaType,
                            fileSize = null,
                            duration = photo.duration,
                            width = photo.width,
                            height = photo.height
                        )
                    )
                    photoDao.updateUploadStatus(photo.localId, "NONE")
                    enqueuedCount++
                }
            }
        }

        Log.i(TAG, "Enqueued $enqueuedCount media items for backup (batch size: ${photos.size})")
        enqueuedCount
    }

    /**
     * Atomically claims the next eligible queue item for processing by the given worker.
     * Prevents race conditions between concurrent workers.
     *
     * @return The claimed queue item, or null if no eligible items are available.
     */
    suspend fun claimNextItem(
        workerToken: String,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): UploadQueue? = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        // Fetch up to 20 candidate items that are PENDING or ready to retry
        val candidates = uploadQueueDao.getPendingOrRetryable(now, limit = 20)
        for (candidate in candidates) {
            val affected = uploadQueueDao.claimQueueItem(candidate.id, workerToken, now)
            if (affected == 1) {
                photoDao.updateUploadStatus(candidate.localId, "UPLOADING", now)
                return@withContext candidate.copy(
                    status = "IN_PROGRESS",
                    startedAt = now,
                    workManagerId = workerToken
                )
            }
        }
        null
    }

    /**
     * Records a successful upload, updating both queue and Photo records transactionally.
     */
    suspend fun recordSuccess(
        queueId: Long,
        localId: String,
        remoteId: String,
        messageId: Long? = null,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ) = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        database.withTransaction {
            uploadQueueDao.markDone(queueId, now)
            photoDao.updateRemoteIdForLocalId(localId, remoteId)
            photoDao.updateUploadStatus(localId, "DONE", now)
        }
        Log.i(TAG, "Successfully recorded upload completion: queueId=$queueId, localId=$localId, remoteId=$remoteId")
    }

    /**
     * Records an upload error with classification, setting retry delays or marking permanent failures.
     */
    suspend fun recordFailure(
        queueId: Long,
        localId: String,
        error: UploadError,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ) = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        database.withTransaction {
            when (error) {
                is UploadError.FileExceedsLimit -> {
                    uploadQueueDao.markExceedsLimit(queueId, error.errorMessage)
                    photoDao.updateUploadStatus(localId, "EXCEEDS_LIMIT", now)
                    Log.w(TAG, "Permanent error (exceeds limit): queueId=$queueId, localId=$localId")
                }
                is UploadError.SourceMissing -> {
                    uploadQueueDao.markSourceMissing(queueId, error.errorMessage)
                    photoDao.updateUploadStatus(localId, "SOURCE_MISSING", now)
                    Log.w(TAG, "Permanent error (source missing): queueId=$queueId, localId=$localId")
                }
                is UploadError.InvalidCredentials -> {
                    uploadQueueDao.markFailed(queueId, "Invalid Telegram credentials: ${error.errorMessage}")
                    photoDao.updateUploadStatus(localId, "FAILED", now)
                    Log.e(TAG, "Permanent error (invalid credentials): queueId=$queueId, localId=$localId")
                }
                is UploadError.RateLimited -> {
                    val waitSeconds = if (error.retryAfterSeconds > 0) error.retryAfterSeconds else 60
                    val retryAt = now + (waitSeconds * 1000L)
                    val msg = "Rate limited by Telegram (retry after ${waitSeconds}s)"
                    uploadQueueDao.markRateLimited(queueId, msg, retryAt)
                    photoDao.updateUploadStatus(localId, "FAILED", now)
                    Log.w(TAG, "Rate limited: queueId=$queueId, retry in ${waitSeconds}s")
                }
                is UploadError.NetworkUnavailable, is UploadError.ServerError, is UploadError.ApiError, is UploadError.UnsupportedFormat -> {
                    val currentItem = uploadQueueDao.getById(queueId)
                    val retryCount = currentItem?.retryCount ?: 0
                    if (retryCount >= MAX_RETRIES) {
                        val msg = "Maximum retries ($MAX_RETRIES) exhausted: ${error.errorMessage}"
                        uploadQueueDao.markFailed(queueId, msg)
                        photoDao.updateUploadStatus(localId, "FAILED", now)
                        Log.e(TAG, "Max retries reached: queueId=$queueId, localId=$localId")
                    } else {
                        val backoffMs = calculateBackoffMs(retryCount)
                        val retryAt = now + backoffMs
                        uploadQueueDao.markRetryWait(queueId, error.errorMessage, retryAt)
                        photoDao.updateUploadStatus(localId, "FAILED", now)
                        Log.w(TAG, "Transient failure (attempt ${retryCount + 1}/$MAX_RETRIES): retry in ${backoffMs / 1000}s")
                    }
                }
            }
        }
    }

    /**
     * Recovers abandoned or interrupted IN_PROGRESS uploads left dangling by process termination or crashes.
     * Avoids blindly resetting active worker tasks by checking stale threshold duration.
     *
     * @return Number of recovered records.
     */
    suspend fun recoverStaleUploads(
        staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        val cutoff = now - staleThresholdMs
        val staleItems = uploadQueueDao.getStaleInProgress(cutoff)
        var recovered = 0

        database.withTransaction {
            for (item in staleItems) {
                val affected = uploadQueueDao.resetStaleItem(item.id)
                if (affected > 0) {
                    photoDao.updateUploadStatus(item.localId, "FAILED", now)
                    recovered++
                }
            }
        }

        if (recovered > 0) {
            Log.w(TAG, "Recovered $recovered stale IN_PROGRESS upload records left from previous session")
        }
        recovered
    }

    /**
     * Releases worker ownership on worker interruption (e.g. WorkManager isStopped).
     * Resets items currently held by this specific worker to PENDING.
     */
    suspend fun releaseWorkerClaims(
        workerToken: String,
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val affected = uploadQueueDao.releaseWorkerClaims(workerToken)
        if (affected > 0) {
            Log.i(TAG, "Released $affected queue claims for worker $workerToken")
        }
        affected
    }

    /**
     * Renews the active worker lease / heartbeat for an item currently IN_PROGRESS.
     * Prevents long-running uploads (e.g. large videos taking > 10 minutes) from being
     * prematurely reclaimed by recoverStaleUploads().
     *
     * @return true if the lease was successfully renewed, false if ownership was lost.
     */
    suspend fun renewLease(
        queueId: Long,
        workerToken: String,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): Boolean = withContext(Dispatchers.IO) {
        val affected = database.uploadQueueDao().updateLease(queueId, workerToken, now)
        affected > 0
    }

    /**
     * Retrieves the earliest scheduled retry timestamp across all RETRY_WAIT items.
     * Used to schedule delayed WorkManager execution so retries run promptly when due.
     */
    suspend fun getEarliestScheduledRetry(
        database: WhDatabase = DbHolder.database
    ): Long? = withContext(Dispatchers.IO) {
        database.uploadQueueDao().getEarliestScheduledRetry()
    }

    /**
     * User action: retries a single failed, cancelled, or retry-waiting item.
     */
    suspend fun retryQueueItem(
        queueId: Long,
        database: WhDatabase = DbHolder.database
    ) = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        database.withTransaction {
            val item = uploadQueueDao.getById(queueId) ?: return@withTransaction
            uploadQueueDao.resetToPending(queueId)
            photoDao.updateUploadStatus(item.localId, "NONE")
        }
    }

    /**
     * User action: retries all eligible failed, retry-wait, and cancelled queue items.
     */
    suspend fun retryAllEligible(
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        var resetCount = 0

        database.withTransaction {
            resetCount = uploadQueueDao.resetAllEligibleToPending()
        }
        resetCount
    }

    /**
     * User action: cancels a pending or retry-waiting queue item.
     */
    suspend fun cancelQueueItem(
        queueId: Long,
        database: WhDatabase = DbHolder.database
    ) = withContext(Dispatchers.IO) {
        val uploadQueueDao = database.uploadQueueDao()
        val photoDao = database.photoDao()

        database.withTransaction {
            val item = uploadQueueDao.getById(queueId) ?: return@withTransaction
            uploadQueueDao.markCancelled(queueId)
            photoDao.updateUploadStatus(item.localId, "NONE")
        }
    }

    /**
     * User action: clears completed upload history without touching media files on device.
     */
    suspend fun clearCompleted(
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        database.uploadQueueDao().deleteCompleted()
    }

    /**
     * Flow of all upload queue entries for UI display.
     */
    fun getAllFlow(database: WhDatabase = DbHolder.database): Flow<List<UploadQueue>> {
        return database.uploadQueueDao().getAllFlow()
    }
}
