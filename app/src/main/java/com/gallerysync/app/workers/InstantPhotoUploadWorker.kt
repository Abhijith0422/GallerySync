package com.gallerysync.app.workers

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gallerysync.app.R
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.upload.UploadQueueRepository
import com.gallerysync.app.utils.getExtFromMimeType
import com.gallerysync.app.utils.getFileName
import com.gallerysync.app.utils.getMimeTypeFromUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Worker handling instant/selective photo upload triggers.
 *
 * In accordance with Phase 6.1 architecture:
 * 1. Persists discovered media into the authoritative Room upload queue.
 * 2. Requests the single authoritative queue-processing worker (PeriodicPhotoBackupWorker).
 * 3. Does not run an independent Telegram upload loop, preventing parallel upload races.
 */
class InstantPhotoUploadWorker(
    private val appContext: Context,
    private val params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!Preferences.isTelegramConfigured()) {
                Log.w("PhotoUpload", "Telegram not configured, aborting instant upload")
                return@withContext Result.failure()
            }

            val photoUriString = params.inputData.getString(KEY_PHOTO_URI) ?: return@withContext Result.failure()
            val photoUri = photoUriString.toUri()
            val fileName = getFileName(appContext.contentResolver, photoUri)
            val uploadType = params.inputData.getString(KEY_UPLOAD_TYPE) ?: "instant"

            // 1. Resolve or create Photo record
            val photoDao = DbHolder.database.photoDao()
            val photo = photoDao.getAll().find { it.pathUri == photoUriString }
            val effectivePhoto = photo ?: run {
                val mime = getMimeTypeFromUri(appContext.contentResolver, photoUri) ?: "image/jpeg"
                val ext = getExtFromMimeType(mime) ?: "jpg"
                val id = photoUri.lastPathSegment ?: UUID.randomUUID().toString()
                val p = Photo(
                    localId = id,
                    photoType = ext,
                    pathUri = photoUriString,
                    mediaType = if (mime.startsWith("video/")) "VIDEO" else "IMAGE"
                )
                photoDao.insertPhotos(p)
                photoDao.getPhotoByLocalId(id) ?: p
            }

            if (effectivePhoto.uploadStatus == "DONE" || effectivePhoto.remoteId != null) {
                Log.d("PhotoUpload", "Skipping: already uploaded (${effectivePhoto.localId})")
                return@withContext Result.success()
            }

            // 2. Enqueue into authoritative Room queue before upload begins
            UploadQueueRepository.enqueueMediaForBackup(appContext, listOf(effectivePhoto), workerType = uploadType)

            // 3. Request the single authoritative queue-processing worker
            WorkModule.PeriodicBackup.enqueue(type = uploadType)

            setProgress(
                workDataOf(
                    "progress" to "enqueued",
                    KEY_PHOTO_URI to photoUriString,
                    KEY_FILE_NAME to fileName,
                    KEY_UPLOAD_TYPE to uploadType
                )
            )

            Log.d("PhotoUpload", "Instant media enqueued and queue processing requested: ${effectivePhoto.localId}")
            Result.success(
                workDataOf(
                    KEY_PHOTO_URI to photoUriString,
                    KEY_FILE_NAME to fileName,
                    KEY_UPLOAD_TYPE to uploadType
                )
            )
        } catch (e: Exception) {
            Log.e("PhotoUpload", "Fatal exception in InstantPhotoUploadWorker", e)
            Result.failure()
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
        const val KEY_PHOTO_URI = "KEY_PHOTO_URI"
        const val KEY_FILE_NAME = "KEY_FILE_NAME"
        const val KEY_UPLOAD_TYPE = "KEY_UPLOAD_TYPE"
    }
}
