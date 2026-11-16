package com.gallerysync.app.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gallerysync.app.R
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.restoration.RestorationExecutionResult
import com.gallerysync.app.data.restoration.VideoRestorationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker executing resilient, persistent background restoration of large chunked
 * and normal videos from Telegram to device storage.
 */
class VideoRestorationWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val restorationId = inputData.getString(KEY_RESTORATION_ID)
        if (restorationId.isNullOrBlank()) {
            Log.e(TAG, "Missing KEY_RESTORATION_ID in inputData")
            return@withContext Result.failure(workDataOf(KEY_RESULT_ERROR to "Missing restorationId"))
        }

        Log.i(TAG, "Starting VideoRestorationWorker for restorationId: $restorationId (attempt: $runAttemptCount)")

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting foreground info: ${e.message}")
        }

        val botToken = Preferences.getEncryptedString(Preferences.botToken, "")
        if (botToken.isBlank()) {
            val err = "Telegram bot token is not configured"
            Log.e(TAG, err)
            return@withContext Result.failure(workDataOf(KEY_RESULT_ERROR to err))
        }

        val manager = VideoRestorationManager.defaultInstance

        val result = manager.executeRestoration(
            context = context,
            restorationId = restorationId,
            botToken = botToken,
            isStopped = { isStopped },
            onProgress = { job ->
                val total = job.expectedFileSize.takeIf { it > 0L } ?: 1L
                val progressPercent = ((job.downloadedBytes.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                try {
                    setProgressAsync(
                        workDataOf(
                            "status" to job.status.name,
                            "progress" to progressPercent,
                            "downloadedBytes" to job.downloadedBytes,
                            "totalBytes" to job.expectedFileSize,
                            "verifiedChunks" to job.verifiedChunks,
                            "totalChunks" to job.totalChunks
                        )
                    )
                } catch (_: Exception) {}
            }
        )

        when (result) {
            is RestorationExecutionResult.Success -> {
                Log.i(TAG, "✅ Video restoration $restorationId succeeded!")
                Result.success(
                    workDataOf(
                        KEY_RESTORATION_ID to restorationId,
                        "total_bytes" to result.totalBytes,
                        "destination_uri" to (result.destinationUri ?: "")
                    )
                )
            }

            is RestorationExecutionResult.AwaitingDestination -> {
                Log.i(TAG, "Video reconstructed for $restorationId. Awaiting user destination selection.")
                Result.success(
                    workDataOf(
                        KEY_RESTORATION_ID to restorationId,
                        "status" to "AWAITING_DESTINATION"
                    )
                )
            }

            is RestorationExecutionResult.RateLimited -> {
                Log.w(TAG, "Rate limited on restoration: retrying after ${result.retryAfterSeconds}s")
                Result.retry()
            }

            is RestorationExecutionResult.Cancelled -> {
                Log.i(TAG, "Restoration $restorationId was cancelled")
                Result.failure(workDataOf(KEY_RESULT_ERROR to "Restoration cancelled"))
            }

            is RestorationExecutionResult.InsufficientStorage -> {
                Log.e(TAG, "Restoration $restorationId failed: Insufficient storage")
                Result.failure(workDataOf(KEY_RESULT_ERROR to "Insufficient storage space"))
            }

            is RestorationExecutionResult.VerificationFailed -> {
                Log.e(TAG, "Restoration $restorationId failed cryptographic verification: ${result.reason}")
                Result.failure(workDataOf(KEY_RESULT_ERROR to result.reason))
            }

            is RestorationExecutionResult.Error -> {
                Log.e(TAG, "Restoration $restorationId error: ${result.message}")
                if (result.isRetryable && runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_RESULT_ERROR to result.message))
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            context,
            WorkModule.NOTIFICATION_ID_RESTORATION,
            context.getString(R.string.restoring_video_notification)
        )
    }

    companion object {
        private const val TAG = "VideoRestorationWorker"
        const val KEY_RESTORATION_ID = "restoration_id"
        const val KEY_RESULT_ERROR = "error"
    }
}
