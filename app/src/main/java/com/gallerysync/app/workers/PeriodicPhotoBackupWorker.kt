package com.gallerysync.app.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gallerysync.app.R
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.upload.TelegramUploadDispatcher
import com.gallerysync.app.data.upload.UploadQueueRepository
import com.gallerysync.app.utils.ContentHasher
import com.gallerysync.app.utils.NotificationHelper
import com.gallerysync.app.utils.generatePreview
import com.gallerysync.app.utils.getBucketNamesForIds
import com.gallerysync.app.utils.getDateAddedForIds
import com.gallerysync.app.utils.getFileName
import com.gallerysync.app.utils.isSyncImagePreviewEnabled
import com.gallerysync.app.utils.uploadPreviewFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PeriodicPhotoBackupWorker(
    private val appContext: Context,
    private val params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val channelId: Long = Preferences.getEncryptedLong(Preferences.channelId, 0L)
    private val botToken: String get() = Preferences.getEncryptedString(Preferences.botToken, "")
    private val botApi: BotApi = BotApi
    private val workerToken = UUID.randomUUID().toString()

    override suspend fun doWork(): Result {
        try {
            if (!Preferences.isTelegramConfigured()) {
                Log.w("PeriodicBackup", "Telegram bot or channel not configured, aborting backup")
                return Result.failure()
            }

            // 1. Recover any abandoned IN_PROGRESS records from dead workers / previous sessions
            UploadQueueRepository.recoverStaleUploads()

            val uploadType = params.inputData.getString(KEY_UPLOAD_TYPE) ?: "periodic"
            val isManual = uploadType == "manual_backup"

            // Centralized network policy check (Phase 7)
            val networkStatus = com.gallerysync.app.data.upload.SyncNetworkPolicy.evaluateNetwork(appContext, isManual)
            if (!networkStatus.isEligible) {
                Log.w("PeriodicBackup", "Network not eligible for upload ($networkStatus, isManual=$isManual). Leaving queue items pending.")
                return Result.retry()
            }

            val photoDao = DbHolder.database.photoDao()

            // 2. Discover pending media items adhering to user sync preferences and folder exclusions
            val allPending = photoDao.getAllPendingUpload()
            val excludedBucketNames = Preferences.getExcludedBucketNames()
            val syncMode = Preferences.getSyncMode()
            val newOnlyTimestamp = Preferences.getSyncNewOnlyTimestamp()

            val pendingPhotos = when {
                excludedBucketNames.isEmpty() && syncMode != Preferences.SYNC_MODE_NEW_ONLY -> allPending
                else -> {
                    val ids = allPending.mapNotNull { it.pathUri.substringAfterLast("/").toLongOrNull() }
                    var filtered = allPending

                    if (excludedBucketNames.isNotEmpty()) {
                        val bucketNameMap = getBucketNamesForIds(appContext.contentResolver, ids)
                        filtered = filtered.filter { photo ->
                            val photoId = photo.pathUri.substringAfterLast("/")
                            val bucketName = bucketNameMap[photoId]
                            bucketName !in excludedBucketNames
                        }
                    }

                    if (syncMode == Preferences.SYNC_MODE_NEW_ONLY && newOnlyTimestamp > 0L) {
                        val dateAddedMap = getDateAddedForIds(appContext.contentResolver, ids)
                        filtered = filtered.filter { photo ->
                            val photoId = photo.pathUri.substringAfterLast("/")
                            val dateAdded = dateAddedMap[photoId] ?: 0L
                            dateAdded >= newOnlyTimestamp
                        }
                    }
                    filtered
                }
            }

            // 3. Enqueue discovered media into the persistent Room queue before upload begins
            if (pendingPhotos.isNotEmpty()) {
                UploadQueueRepository.enqueueMediaForBackup(appContext, pendingPhotos, workerType = uploadType)
            }

            return withContext(Dispatchers.IO) {
                try {
                    var successCount = 0
                    var failedCount = 0

                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_CURRENT to 0,
                            KEY_PROGRESS_MAX to pendingPhotos.size,
                            KEY_CURRENT_FILE_URI to ""
                        )
                    )

                    // 4. Authoritative queue loop: claim and process items one at a time
                    while (!isStopped) {
                        // In-flight network check: verify network is still eligible before processing next item
                        if (!com.gallerysync.app.data.upload.SyncNetworkPolicy.isNetworkEligible(appContext, isManual)) {
                            Log.w("PeriodicBackup", "Network became ineligible mid-batch. Halting queue processing safely.")
                            break
                        }

                        val queueItem = UploadQueueRepository.claimNextItem(workerToken) ?: break

                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to (successCount + failedCount),
                                KEY_PROGRESS_MAX to pendingPhotos.size,
                                KEY_CURRENT_FILE_URI to queueItem.pathUri
                            )
                        )

                        val success = processQueueItem(queueItem, uploadType)
                        if (success) {
                            successCount++
                        } else {
                            failedCount++
                        }

                        delay(DELAY_BETWEEN_CHUNKS_MS)
                    }

                    Log.d("PeriodicBackup", "Queue processing cycle complete: $successCount succeeded, $failedCount failed")

                    // Commit any pending catalog entries to the Telegram Master Index
                    try {
                        val pendingCount = DbHolder.database.pendingCatalogEntryDao().getPendingCount()
                        if (pendingCount > 0 && botToken.isNotBlank() && channelId != 0L) {
                            Log.i("PeriodicBackup", "Committing $pendingCount pending catalog entries to Telegram Master Index...")
                            com.gallerysync.app.data.catalog.MasterIndexManager.defaultInstance.commitPendingEntries(
                                context = appContext,
                                botToken = botToken,
                                channelId = channelId
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("PeriodicBackup", "Failed to commit master index at end of batch: ${e.message}", e)
                    }

                    // Schedule delayed execution if any items are in RETRY_WAIT
                    val earliestRetry = UploadQueueRepository.getEarliestScheduledRetry()
                    if (earliestRetry != null) {
                        val now = System.currentTimeMillis()
                        val delayMs = (earliestRetry - now).coerceAtLeast(1000L)
                        Log.i("PeriodicBackup", "Scheduling next delayed retry in ${delayMs / 1000}s")
                        WorkModule.PeriodicBackup.scheduleDelayedRetry(delayMs)
                    }

                    if (isStopped) {
                        Result.retry()
                    } else {
                        Result.success(
                            workDataOf(
                                "succeeded" to successCount,
                                "failed" to failedCount
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("PeriodicBackup", "Queue backup worker exception: ${e.message}", e)
                    Result.retry()
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                UploadQueueRepository.releaseWorkerClaims(workerToken)
            }
        }
    }

    private suspend fun processQueueItem(
        queueItem: UploadQueue,
        uploadType: String
    ): Boolean = coroutineScope {
        val photoDao = DbHolder.database.photoDao()
        val remotePhotoDao = DbHolder.database.remotePhotoDao()
        val uri = queueItem.pathUri.toUri()

        // Renewable worker lease / heartbeat: refreshes startedAt every 60s
        // Ensures active uploads taking > 10 minutes (large videos) are never reclaimed as stale
        val heartbeatJob = launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                UploadQueueRepository.renewLease(queueItem.id, workerToken)
            }
        }

        try {
            Log.d("PeriodicBackup", "Processing queue item ${queueItem.id} (localId=${queueItem.localId}, type=${queueItem.mediaType})")

            // 1. Re-check if already completed
            val current = photoDao.getPhotoByLocalId(queueItem.localId) ?: run {
                UploadQueueRepository.recordFailure(
                    queueItem.id,
                    queueItem.localId,
                    TelegramUploadDispatcher.UploadError.SourceMissing("Photo not found in database: ${queueItem.localId}")
                )
                return@coroutineScope false
            }

            if (current.uploadStatus == "DONE" || current.remoteId != null) {
                UploadQueueRepository.recordSuccess(queueItem.id, current.localId, current.remoteId ?: "ALREADY_UPLOADED")
                return@coroutineScope true
            }

            // 2. Compute content hash if missing
            val hash = queueItem.contentHash ?: current.contentHash ?: run {
                try {
                    ContentHasher.computeHash(appContext, uri)
                } catch (_: Exception) {
                    null
                }
            }
            if (hash != null && current.contentHash == null) {
                photoDao.updateContentHash(current.localId, hash)
            }

            // 3. Duplicate check in cloud
            if (hash != null) {
                val existing = remotePhotoDao.getByContentHash(hash)
                if (existing != null) {
                    Log.d("PeriodicBackup", "Dedup: ${queueItem.localId} already in cloud (remoteId=${existing.remoteId})")
                    UploadQueueRepository.recordSuccess(queueItem.id, current.localId, existing.remoteId)
                    return@coroutineScope true
                }
            }

            // 4. Resolve folder / topic for forum organization
            var messageThreadId: Long? = null
            var topicName: String? = null
            try {
                val bucketName = com.gallerysync.app.utils.getBucketName(appContext.contentResolver, uri)
                if (bucketName != null && bucketName.isNotBlank()) {
                    topicName = bucketName
                    messageThreadId = botApi.topicCache[bucketName]
                    if (messageThreadId == null) {
                        messageThreadId = remotePhotoDao.getTopicIdByName(bucketName)
                        if (messageThreadId != null) {
                            botApi.topicCache[bucketName] = messageThreadId
                        } else {
                            messageThreadId = botApi.createForumTopic(channelId, bucketName)
                            if (messageThreadId != null) {
                                botApi.topicCache[bucketName] = messageThreadId
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PeriodicBackup", "Failed to resolve topic for ${queueItem.localId}", e)
            }

            // Fall back to user-configured forum topic ID if no bucket topic was resolved
            val effectiveTopicId = messageThreadId ?: Preferences.getEncryptedTopicId()

            // 5. Image preview upload (images only)
            var previewId: String? = null
            var previewMessageId: Long? = null
            if (queueItem.mediaType != "VIDEO" && isSyncImagePreviewEnabled()) {
                try {
                    val previewFile = generatePreview(appContext, uri)
                    if (previewFile != null) {
                        val result = uploadPreviewFile(botApi, channelId, previewFile, hash, effectiveTopicId, topicName)
                        previewId = result.first
                        previewMessageId = result.second
                        if (previewId != null) {
                            photoDao.updatePreviewRemoteId(current.localId, previewId)
                        }
                        previewFile.delete()
                    }
                } catch (e: Throwable) {
                    Log.e("PeriodicBackup", "Preview upload failed for ${queueItem.localId}, continuing", e)
                }
            }

            // 6. Check if media requires chunked upload (e.g. video > 16 MiB or active chunked backup)
            val rawSourceSize = TelegramUploadDispatcher.getSourceFileSize(appContext, uri)
            val isChunkedVideo = (queueItem.mediaType == "VIDEO" || current.mediaType == "VIDEO" || current.photoType in listOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "flv")) && (
                rawSourceSize > com.gallerysync.app.data.chunking.ChunkConfiguration.DIRECT_UPLOAD_MAX_BYTES ||
                com.gallerysync.app.data.upload.ChunkedBackupRepository.getBackupByLocalId(queueItem.localId) != null
            )

            if (isChunkedVideo) {
                val isManual = uploadType == "manual_backup"
                Log.i("PeriodicBackup", "Large video detected ($rawSourceSize bytes, localId=${queueItem.localId}). Routing to ChunkedUploadEngine.")
                val chunkResult = com.gallerysync.app.data.upload.ChunkedUploadEngine.defaultInstance.processBackup(
                    context = appContext,
                    photo = current.copy(contentHash = hash),
                    isManual = isManual,
                    isStopped = { isStopped }
                )
                return@coroutineScope when (chunkResult) {
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.Success -> {
                        if (chunkResult.isAllChunksUploaded) {
                            Log.i("PeriodicBackup", "All ${chunkResult.totalChunks} chunks uploaded for ${chunkResult.backupId}. Generating and uploading manifest...")
                            val manifestRes = com.gallerysync.app.data.catalog.BackupManifestManager.defaultInstance.createAndUploadManifest(
                                context = appContext,
                                backupId = chunkResult.backupId,
                                botToken = botToken
                            )
                            when (manifestRes) {
                                is com.gallerysync.app.data.catalog.ManifestUploadResult.Success -> {
                                    Log.i("PeriodicBackup", "Manifest uploaded for ${chunkResult.backupId} (fileId=${manifestRes.manifestReference.fileId}). Committing to Master Index...")
                                    val commitRes = com.gallerysync.app.data.catalog.MasterIndexManager.defaultInstance.commitPendingEntries(
                                        context = appContext,
                                        botToken = botToken,
                                        channelId = channelId
                                    )
                                    when (commitRes) {
                                        is com.gallerysync.app.data.catalog.MasterIndexCommitResult.Success -> {
                                            Log.i("PeriodicBackup", "Master index committed for ${chunkResult.backupId}. Recording queue success.")
                                            UploadQueueRepository.recordSuccess(
                                                queueId = queueItem.id,
                                                localId = queueItem.localId,
                                                remoteId = "CHUNKED_${chunkResult.backupId}",
                                                messageId = manifestRes.manifestReference.messageId
                                            )
                                            true
                                        }
                                        is com.gallerysync.app.data.catalog.MasterIndexCommitResult.RateLimited -> {
                                            Log.w("PeriodicBackup", "Rate limited on master index commit (${commitRes.retryAfterSeconds}s). Releasing claim for delayed retry.")
                                            val err = TelegramUploadDispatcher.UploadError.RateLimited(commitRes.retryAfterSeconds)
                                            UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                                            false
                                        }
                                        else -> {
                                            val errMsg = (commitRes as? com.gallerysync.app.data.catalog.MasterIndexCommitResult.Error)?.message
                                                ?: (commitRes as? com.gallerysync.app.data.catalog.MasterIndexCommitResult.PinVerificationFailed)?.reason
                                                ?: "Catalog publication pending"
                                            Log.w("PeriodicBackup", "Master index commit deferred: $errMsg. Leaving queue item for retry without re-uploading media.")
                                            DbHolder.database.uploadQueueDao().resetStaleItem(queueItem.id)
                                            false
                                        }
                                    }
                                }
                                is com.gallerysync.app.data.catalog.ManifestUploadResult.RateLimited -> {
                                    val err = TelegramUploadDispatcher.UploadError.RateLimited(manifestRes.retryAfterSeconds)
                                    UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                                    false
                                }
                                is com.gallerysync.app.data.catalog.ManifestUploadResult.Error -> {
                                    val err = TelegramUploadDispatcher.UploadError.ApiError(0, manifestRes.message, isRetryable = manifestRes.isRetryable)
                                    UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                                    false
                                }
                                is com.gallerysync.app.data.catalog.ManifestUploadResult.NotReady -> {
                                    Log.w("PeriodicBackup", "Manifest not ready: ${manifestRes.reason}")
                                    DbHolder.database.uploadQueueDao().resetStaleItem(queueItem.id)
                                    false
                                }
                            }
                        } else {
                            Log.i("PeriodicBackup", "Chunked upload made progress (${chunkResult.completedChunks}/${chunkResult.totalChunks}) but not finished. Releasing claim and scheduling continuation.")
                            DbHolder.database.uploadQueueDao().resetStaleItem(queueItem.id)
                            WorkModule.PeriodicBackup.enqueue(type = uploadType)
                            false
                        }
                    }
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.Yielded -> {
                        Log.i("PeriodicBackup", "Chunked upload yielded at boundary (${chunkResult.completedChunks}/${chunkResult.totalChunks} chunks): ${chunkResult.reason}. Releasing claim and scheduling continuation.")
                        DbHolder.database.uploadQueueDao().resetStaleItem(queueItem.id)
                        WorkModule.PeriodicBackup.enqueue(type = uploadType)
                        false
                    }
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.RateLimited -> {
                        val waitSec = chunkResult.retryAfterSeconds
                        val err = TelegramUploadDispatcher.UploadError.RateLimited(waitSec)
                        UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                        false
                    }
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.TransientError -> {
                        val err = TelegramUploadDispatcher.UploadError.ApiError(0, chunkResult.message, isRetryable = true)
                        UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                        false
                    }
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.PermanentError,
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.ConfigurationMismatch,
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.SourceModified -> {
                        val msg = when (chunkResult) {
                            is com.gallerysync.app.data.upload.ChunkedUploadResult.ConfigurationMismatch -> chunkResult.reason
                            is com.gallerysync.app.data.upload.ChunkedUploadResult.SourceModified -> chunkResult.reason
                            is com.gallerysync.app.data.upload.ChunkedUploadResult.PermanentError -> chunkResult.message
                            else -> "Chunked upload failed"
                        }
                        val err = TelegramUploadDispatcher.UploadError.ApiError(0, msg, isRetryable = false)
                        UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, err)
                        false
                    }
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.Cancelled,
                    is com.gallerysync.app.data.upload.ChunkedUploadResult.NetworkUnavailable -> {
                        DbHolder.database.uploadQueueDao().resetStaleItem(queueItem.id)
                        false
                    }
                }
            }

            // 7. Perform standard upload via TelegramUploadDispatcher
            val uploadResult = TelegramUploadDispatcher.uploadMedia(
                context = appContext,
                photo = current.copy(contentHash = hash),
                botApi = botApi,
                channelId = channelId,
                uploadType = uploadType,
                messageThreadId = effectiveTopicId,
                topicName = topicName,
                previewId = previewId,
                previewMessageId = previewMessageId
            ) as TelegramUploadDispatcher.UploadResult.Success

            // 7. Record success in repository
            UploadQueueRepository.recordSuccess(queueItem.id, queueItem.localId, uploadResult.fileId, uploadResult.messageId)
            true

        } catch (e: TelegramUploadDispatcher.UploadError) {
            UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, e)
            if (e is TelegramUploadDispatcher.UploadError.FileExceedsLimit) {
                val fileName = getFileName(appContext.contentResolver, uri) ?: queueItem.localId
                NotificationHelper.showUploadExceedsLimitNotification(appContext, fileName, e.actualSize)
            }
            false
        } catch (e: Exception) {
            val generalError = TelegramUploadDispatcher.UploadError.ApiError(0, e.message ?: "Unknown upload exception")
            UploadQueueRepository.recordFailure(queueItem.id, queueItem.localId, generalError)
            false
        } finally {
            heartbeatJob.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            appContext,
            WorkModule.NOTIFICATION_ID_BACKUP,
            appContext.getString(R.string.backing_up_photos)
        )
    }

    companion object {
        const val KEY_COMPRESSION_THRESHOLD = "KEY_COMPRESSION_THRESHOLD"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_MAX = "progress_max"
        const val KEY_CURRENT_FILE_URI = "current_file_uri"
        const val KEY_UPLOAD_TYPE = "upload_type"
        const val DELAY_BETWEEN_CHUNKS_MS = 1000L
        const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}
