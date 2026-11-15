package com.gallerysync.app.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gallerysync.app.R
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.mediastore.MediaStoreScanner
import com.gallerysync.app.utils.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that performs resumable batch synchronization between
 * MediaStore (images and videos) and Room's [photos] table.
 */
class SyncDbMediaStoreWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        try {
            if (!PermissionManager.hasAnyMediaAccess(context)) {
                Log.w(TAG, "No media permissions granted, skipping MediaStore sync")
                return@withContext Result.success()
            }

            Log.i(TAG, "Starting unified MediaStore sync (images + videos)...")
            val photoDao = DbHolder.database.photoDao()

            // 1. One-time fix for any legacy records with video extensions
            runCatching {
                val fixedCount = photoDao.fixLegacyVideoMediaTypes()
                if (fixedCount > 0) {
                    Log.i(TAG, "Updated $fixedCount legacy video records to mediaType=VIDEO")
                }
            }

            // 2. Determine initial baseline state: fresh install/reinstall vs ongoing scan
            val isBaselineDone = com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.isBaselineCompleted(DbHolder.database)
            val baselineTimestamp = com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.getBaselineTimestamp(DbHolder.database)
            val scanStartTime = System.currentTimeMillis()

            // 3. Stream and index media in bounded batches of 200
            var totalIndexed = 0
            val totalScanned = MediaStoreScanner.scanAllInBatches(context, batchSize = 200) { batch ->
                val photos = batch.map { item ->
                    val itemTimestampMs = maxOf(item.dateAdded, item.dateModified) * 1000L
                    // Items existing at or before baseline are held; genuinely new items after baseline are marked NONE
                    val status = if (!isBaselineDone || (baselineTimestamp > 0L && itemTimestampMs <= baselineTimestamp)) {
                        com.gallerysync.app.data.localdb.entities.Photo.STATUS_HOLDING
                    } else {
                        com.gallerysync.app.data.localdb.entities.Photo.STATUS_NONE
                    }
                    item.toPhoto(defaultUploadStatus = status)
                }
                val insertedRowIds = photoDao.insertPhotos(*photos.toTypedArray())
                val newInserts = insertedRowIds.count { it != -1L }
                totalIndexed += newInserts

                // For existing items that were inserted before v11, backfill dimensions/duration if missing
                batch.forEach { item ->
                    if (item.width != null || item.height != null || item.duration != null) {
                        photoDao.updateMediaMetadataIfMissing(
                            localId = item.id,
                            mediaType = item.mediaType,
                            width = item.width,
                            height = item.height,
                            duration = item.duration
                        )
                    }
                }
            }

            // If this was the initial baseline scan, mark baseline completed now
            if (!isBaselineDone) {
                com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.markBaselineCompleted(
                    database = DbHolder.database,
                    mediaCount = totalScanned,
                    timestamp = scanStartTime
                )
                Log.i(TAG, "Completed initial baseline MediaStore scan: $totalScanned items placed in HOLDING state (baselineTimestamp=$scanStartTime).")
            }

            Log.i(TAG, "MediaStore scan finished: scanned=$totalScanned, newly indexed=$totalIndexed (baselineDone=$isBaselineDone)")

            // 4. Safe deletion check (strictly guarded: only runs if FullAccess is granted)
            MediaStoreScanner.pruneDeletedMedia(context, photoDao)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing MediaStore to Room", e)
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            context,
            WorkModule.NOTIFICATION_ID_SYNC,
            context.getString(R.string.syncing_all_photos)
        )
    }

    companion object {
        private const val TAG = "SyncDbMediaStoreWorker"
    }
}
