package com.gallerysync.app.data.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.gallerysync.app.api.TelegramChunkApi
import com.gallerysync.app.api.TelegramChunkUploadResult
import com.gallerysync.app.data.chunking.ChunkConfiguration
import com.gallerysync.app.data.chunking.ChunkDescriptor
import com.gallerysync.app.data.chunking.ChunkEngine
import com.gallerysync.app.data.chunking.ChunkSource
import com.gallerysync.app.data.chunking.ChunkedRequestBody
import com.gallerysync.app.data.chunking.HashingResult
import com.gallerysync.app.data.chunking.SourceMetadata
import com.gallerysync.app.data.chunking.SourceModificationDetector
import com.gallerysync.app.data.chunking.SourceValidationResult
import com.gallerysync.app.data.chunking.UriChunkSource
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * High-level orchestration engine for large video backups via chunked Telegram documents.
 *
 * Responsibilities:
 * 1. Resolves or creates parent [ChunkedBackup] and child [VideoChunk] records.
 * 2. Enforces bot and channel configuration consistency across restarts.
 * 3. Hashes video in a single pass (master SHA-256 and 16 MiB chunk digests).
 * 4. Verifies in-flight byte count and chunk checksums during transmission.
 * 5. Sequentially uploads 16 MiB chunks via Telegram Bot API sendDocument.
 * 6. Enforces rate limits (>= 1,000 ms between chunk messages, honours HTTP 429 retry_after).
 * 7. Transactionally persists confirmed Telegram file IDs and message IDs.
 * 8. Recovers interrupted uploads from the first non-completed chunk.
 * 9. Transitions parent backup to [ChunkedBackupStatus.VERIFYING] upon all chunks completion (ready for Phase 10.4 manifest).
 */
class ChunkedUploadEngine(
    private val chunkApi: TelegramChunkApi = TelegramChunkApi(),
    private val repository: ChunkedBackupRepository = ChunkedBackupRepository,
    private val database: WhDatabase = DbHolder.database
) {

    /**
     * Determines whether a photo/video requires chunked backup.
     */
    fun requiresChunkedBackup(mediaType: String, fileSize: Long): Boolean {
        val isVideo = mediaType == "VIDEO"
        return isVideo && fileSize > TelegramUploadDispatcher.MAX_TELEGRAM_FILE_SIZE_BYTES
    }

    /**
     * Main entry point called by the authoritative worker (e.g. PeriodicPhotoBackupWorker).
     *
     * @param context Application context
     * @param photo Media photo entity to back up
     * @param isManual Whether this is a user-initiated manual backup
     * @param isStopped Callback checking if the parent WorkManager worker has been cancelled
     * @param onProgress Callback invoked after each chunk confirms upload
     * @param maxExecutionDurationMs Maximum duration to run before voluntarily yielding at a chunk boundary
     */
    suspend fun processBackup(
        context: Context,
        photo: Photo,
        isManual: Boolean = false,
        isStopped: () -> Boolean = { false },
        onProgress: ((progress: ChunkedBackupProgress) -> Unit)? = null,
        maxExecutionDurationMs: Long = DEFAULT_MAX_EXECUTION_DURATION_MS
    ): ChunkedUploadResult = withContext(Dispatchers.IO) {
        val executionStartTime = System.currentTimeMillis()
        val uri = photo.pathUri.toUri()
        val botToken = Preferences.getEncryptedString(Preferences.botToken, "")
        val channelId = Preferences.getEncryptedLong(Preferences.channelId, 0L)
        val botId = TelegramChunkApi.extractBotId(botToken)

        if (botToken.isBlank() || channelId == 0L) {
            return@withContext ChunkedUploadResult.PermanentError(
                backupId = "",
                message = "Telegram credentials or channel ID not configured"
            )
        }

        // 1. Resolve or create parent ChunkedBackup
        val source = ChunkEngine.createSource(context, uri)
        val backup = try {
            resolveOrCreateBackup(context, photo, source, channelId, botId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve or create backup for ${photo.localId}", e)
            return@withContext ChunkedUploadResult.PermanentError(
                backupId = "",
                message = "Failed to initialize backup: ${e.message}",
                cause = e
            )
        }

        // 2. Validate channel & bot identity consistency
        if (backup.channelId != channelId || (backup.botId != 0L && botId != 0L && backup.botId != botId)) {
            val mismatch = "Target destination changed: backup created for channel ${backup.channelId}/bot ${backup.botId}, currently configured for channel $channelId/bot $botId"
            Log.e(TAG, mismatch)
            repository.updateBackupStatus(
                backupId = backup.backupId,
                status = ChunkedBackupStatus.FAILED,
                lastError = mismatch,
                database = database
            )
            return@withContext ChunkedUploadResult.ConfigurationMismatch(backup.backupId, mismatch)
        }

        // 3. Recover any stale UPLOADING chunks from previous process terminations
        repository.recoverStaleUploadingChunks(backup.backupId, database)

        // 4. Validate source accessibility
        val validation = source.validate()
        if (validation !is SourceValidationResult.Valid) {
            val errMsg = "Source media inaccessible: $validation"
            Log.e(TAG, errMsg)
            repository.updateBackupStatus(
                backupId = backup.backupId,
                status = ChunkedBackupStatus.FAILED,
                lastError = errMsg,
                database = database
            )
            return@withContext ChunkedUploadResult.PermanentError(backup.backupId, errMsg)
        }

        // 5. Compute full-file and per-chunk hashes if not yet computed
        val activeBackup = if (backup.status == ChunkedBackupStatus.PENDING || backup.fileSha256 == null) {
            Log.i(TAG, "Computing SHA-256 digests for backup ${backup.backupId} (${backup.fileSize} bytes)")
            repository.updateBackupStatus(backup.backupId, ChunkedBackupStatus.HASHING, database = database)

            val hashingResult = ChunkEngine.hash(source)
            if (hashingResult !is HashingResult.Success) {
                val err = "Hashing failed for ${backup.backupId}: $hashingResult"
                Log.e(TAG, err)
                repository.updateBackupStatus(
                    backupId = backup.backupId,
                    status = ChunkedBackupStatus.FAILED,
                    lastError = err,
                    database = database
                )
                return@withContext ChunkedUploadResult.SourceModified(backup.backupId, err)
            }

            // Persist SHA-256 hashes to Room
            ChunkEngine.persistHashesToRepository(backup.backupId, hashingResult, database)
            repository.getBackupById(backup.backupId, database) ?: backup
        } else {
            backup
        }

        // 6. Inspect pre-flight source snapshot for modifications
        val currentMeta = source.getMetadata()
        if (currentMeta.fileSize != activeBackup.fileSize) {
            val err = "Source file size changed from ${activeBackup.fileSize} to ${currentMeta.fileSize} bytes"
            Log.e(TAG, err)
            repository.updateBackupStatus(
                backupId = activeBackup.backupId,
                status = ChunkedBackupStatus.FAILED,
                lastError = err,
                database = database
            )
            return@withContext ChunkedUploadResult.SourceModified(activeBackup.backupId, err)
        }

        // Ensure backup status is UPLOADING
        if (activeBackup.status != ChunkedBackupStatus.UPLOADING) {
            repository.updateBackupStatus(activeBackup.backupId, ChunkedBackupStatus.UPLOADING, database = database)
        }

        // 7. Sequential chunk upload loop
        val pendingChunks = repository.getPendingChunks(activeBackup.backupId, database = database)

        if (pendingChunks.isEmpty()) {
            val completed = repository.getBackupProgress(activeBackup.backupId, database)?.completedChunks ?: activeBackup.totalChunks
            if (completed >= activeBackup.totalChunks) {
                repository.updateBackupStatus(activeBackup.backupId, ChunkedBackupStatus.VERIFYING, database = database)
                return@withContext ChunkedUploadResult.Success(
                    backupId = activeBackup.backupId,
                    completedChunks = completed,
                    totalChunks = activeBackup.totalChunks,
                    isAllChunksUploaded = true
                )
            }
        }

        Log.i(TAG, "Starting sequential upload: ${pendingChunks.size} pending chunks remaining for ${activeBackup.backupId}")

        for (chunk in pendingChunks) {
            val elapsed = System.currentTimeMillis() - executionStartTime
            val isApproachingTimeout = elapsed >= maxExecutionDurationMs

            // Cancellation and Execution Window checkpoint at chunk boundary
            if (isStopped() || isApproachingTimeout) {
                val reason = if (isStopped()) {
                    "Worker stopped by WorkManager"
                } else {
                    "Yielding before WorkManager execution timeout (${elapsed / 1000}s elapsed)"
                }
                Log.i(TAG, "Chunked upload boundary yield: $reason for backup ${activeBackup.backupId}")
                repository.recoverStaleUploadingChunks(activeBackup.backupId, database)
                val completed = repository.getBackupProgress(activeBackup.backupId, database)?.completedChunks ?: 0
                return@withContext ChunkedUploadResult.Yielded(
                    backupId = activeBackup.backupId,
                    completedChunks = completed,
                    totalChunks = activeBackup.totalChunks,
                    reason = reason
                )
            }

            // In-flight network eligibility checkpoint
            if (!SyncNetworkPolicy.isNetworkEligible(context, isManual)) {
                Log.w(TAG, "Network became ineligible mid-upload. Pausing.")
                repository.recoverStaleUploadingChunks(activeBackup.backupId, database)
                return@withContext ChunkedUploadResult.NetworkUnavailable(activeBackup.backupId, "Network ineligible")
            }

            // Check scheduled retry time
            val now = System.currentTimeMillis()
            if (chunk.scheduledRetryAt != null && chunk.scheduledRetryAt > now) {
                val waitMs = chunk.scheduledRetryAt - now
                if (waitMs > 10_000L) {
                    return@withContext ChunkedUploadResult.RateLimited(
                        backupId = activeBackup.backupId,
                        retryAfterSeconds = (waitMs / 1000L).toInt(),
                        scheduledRetryAt = chunk.scheduledRetryAt
                    )
                } else {
                    delay(waitMs)
                }
            }

            // Mark chunk UPLOADING in Room
            repository.markChunkUploading(chunk.id, database)

            val descriptor = ChunkDescriptor(
                chunkIndex = chunk.chunkIndex,
                startOffset = chunk.startOffset,
                endOffset = chunk.endOffset,
                chunkSize = chunk.chunkSize,
                chunkSha256 = chunk.chunkSha256
            )

            val requestBody = ChunkedRequestBody(
                source = source,
                descriptor = descriptor,
                expectedSha256 = chunk.chunkSha256
            )

            val chunkFileName = String.format("GallerySync_%s_chunk_%05d.bin", activeBackup.backupId, chunk.chunkIndex)

            Log.d(TAG, "Uploading chunk ${chunk.chunkIndex + 1}/${activeBackup.totalChunks} ($chunkFileName, ${chunk.chunkSize} bytes)")

            val uploadResult = chunkApi.sendChunkDocument(
                botToken = botToken,
                channelId = activeBackup.channelId,
                chunkRequestBody = requestBody,
                fileName = chunkFileName,
                expectedSize = chunk.chunkSize,
                messageThreadId = activeBackup.topicId,
                caption = "Part ${chunk.chunkIndex + 1}/${activeBackup.totalChunks}"
            )

            when (uploadResult) {
                is TelegramChunkUploadResult.Success -> {
                    val completed = repository.recordChunkSuccess(
                        chunkId = chunk.id,
                        backupId = activeBackup.backupId,
                        telegramFileId = uploadResult.fileId,
                        telegramMessageId = uploadResult.messageId,
                        database = database
                    )

                    Log.i(TAG, "✅ Chunk ${chunk.chunkIndex + 1}/${activeBackup.totalChunks} confirmed: fileId=${uploadResult.fileId}, messageId=${uploadResult.messageId} (completed $completed/${activeBackup.totalChunks})")

                    // Notify caller of progress
                    val progress = repository.getBackupProgress(activeBackup.backupId, database)
                    if (progress != null) {
                        onProgress?.invoke(progress)
                    }

                    // Telegram rate limit requirement: at least 1,000 ms between successful chunk messages
                    delay(DELAY_BETWEEN_CHUNKS_MS)
                }

                is TelegramChunkUploadResult.RateLimited -> {
                    val retryAt = System.currentTimeMillis() + (uploadResult.retryAfterSeconds * 1000L)
                    repository.recordChunkRetry(chunk.id, uploadResult.description, retryAt, database)
                    repository.updateBackupStatus(
                        backupId = activeBackup.backupId,
                        status = ChunkedBackupStatus.UPLOADING,
                        lastError = uploadResult.description,
                        database = database
                    )
                    Log.w(TAG, "Rate limited on chunk ${chunk.chunkIndex}: retry after ${uploadResult.retryAfterSeconds}s")
                    return@withContext ChunkedUploadResult.RateLimited(
                        backupId = activeBackup.backupId,
                        retryAfterSeconds = uploadResult.retryAfterSeconds,
                        scheduledRetryAt = retryAt
                    )
                }

                is TelegramChunkUploadResult.IntegrityError -> {
                    val errMsg = "Chunk ${chunk.chunkIndex} integrity verification failed: ${uploadResult.message}"
                    Log.e(TAG, errMsg, uploadResult.cause)
                    repository.recordChunkFailure(chunk.id, activeBackup.backupId, errMsg, database)
                    return@withContext ChunkedUploadResult.SourceModified(activeBackup.backupId, errMsg)
                }

                is TelegramChunkUploadResult.Unauthorized, is TelegramChunkUploadResult.PermissionDenied -> {
                    val desc = if (uploadResult is TelegramChunkUploadResult.Unauthorized) uploadResult.description else (uploadResult as TelegramChunkUploadResult.PermissionDenied).description
                    Log.e(TAG, "Permanent credential/permission error: $desc")
                    repository.recordChunkFailure(chunk.id, activeBackup.backupId, desc, database)
                    return@withContext ChunkedUploadResult.PermanentError(activeBackup.backupId, desc)
                }

                is TelegramChunkUploadResult.NetworkError, is TelegramChunkUploadResult.ServerError, is TelegramChunkUploadResult.InvalidResponse -> {
                    val errorMsg = when (uploadResult) {
                        is TelegramChunkUploadResult.NetworkError -> uploadResult.message
                        is TelegramChunkUploadResult.ServerError -> uploadResult.description
                        is TelegramChunkUploadResult.InvalidResponse -> uploadResult.reason
                        else -> "Upload error"
                    }
                    val backoff = UploadQueueRepository.calculateBackoffMs(chunk.retryCount)
                    val retryAt = System.currentTimeMillis() + backoff
                    repository.recordChunkRetry(chunk.id, errorMsg, retryAt, database)
                    Log.w(TAG, "Transient failure on chunk ${chunk.chunkIndex}: $errorMsg. Retry in ${backoff / 1000}s")
                    return@withContext ChunkedUploadResult.TransientError(activeBackup.backupId, errorMsg, retryAt)
                }

                is TelegramChunkUploadResult.Cancelled -> {
                    repository.recoverStaleUploadingChunks(activeBackup.backupId, database)
                    return@withContext ChunkedUploadResult.Cancelled(activeBackup.backupId, uploadResult.message)
                }
            }
        }

        // 8. Re-evaluate overall progress
        val finalProgress = repository.getBackupProgress(activeBackup.backupId, database)
        val allDone = (finalProgress?.completedChunks ?: 0) >= activeBackup.totalChunks

        if (allDone) {
            // All chunks uploaded! Transition to VERIFYING (Phase 10.4 will generate manifest & update index)
            repository.updateBackupStatus(activeBackup.backupId, ChunkedBackupStatus.VERIFYING, database = database)
            Log.i(TAG, "All ${activeBackup.totalChunks} chunks uploaded for backup ${activeBackup.backupId}. State transitioned to VERIFYING.")
            ChunkedUploadResult.Success(
                backupId = activeBackup.backupId,
                completedChunks = activeBackup.totalChunks,
                totalChunks = activeBackup.totalChunks,
                isAllChunksUploaded = true
            )
        } else {
            ChunkedUploadResult.Success(
                backupId = activeBackup.backupId,
                completedChunks = finalProgress?.completedChunks ?: 0,
                totalChunks = activeBackup.totalChunks,
                isAllChunksUploaded = false
            )
        }
    }

    private suspend fun resolveOrCreateBackup(
        context: Context,
        photo: Photo,
        source: ChunkSource,
        channelId: Long,
        botId: Long
    ): ChunkedBackup {
        val existing = repository.getBackupByLocalId(photo.localId, database)
        if (existing != null) {
            return existing
        }

        val metadata = source.getMetadata()
        val fileSize = if (metadata.fileSize > 0L) metadata.fileSize else photo.duration ?: 0L
        require(fileSize > 0L) { "Invalid file size for chunking: $fileSize" }

        val backupId = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val chunks = repository.calculateChunks(
            backupId = backupId,
            fileSize = fileSize,
            chunkSize = ChunkConfiguration.DEFAULT_CHUNK_SIZE
        )

        val newBackup = ChunkedBackup(
            backupId = backupId,
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = metadata.displayName ?: "video_${photo.localId}.mp4",
            fileSize = fileSize,
            mimeType = metadata.mimeType ?: "video/mp4",
            width = photo.width,
            height = photo.height,
            duration = photo.duration,
            fileSha256 = null,
            chunkSize = ChunkConfiguration.DEFAULT_CHUNK_SIZE,
            totalChunks = chunks.size,
            completedChunks = 0,
            status = ChunkedBackupStatus.PENDING,
            channelId = channelId,
            topicId = null,
            botId = botId,
            botUsername = null
        )

        return repository.createBackupWithChunks(newBackup, chunks, database)
    }

    companion object {
        private const val TAG = "ChunkedUploadEngine"
        const val DELAY_BETWEEN_CHUNKS_MS = 1000L // Minimum 1 second between chunk uploads
        const val DEFAULT_MAX_EXECUTION_DURATION_MS = 8 * 60 * 1000L // 8 minutes (safe margin before WorkManager 10-minute timeout)
        val defaultInstance: ChunkedUploadEngine by lazy { ChunkedUploadEngine() }
    }
}
