package com.gallerysync.app.data.localdb.recovery

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.SyncMetadata
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages reinstallation recovery and prevents duplicate uploads by enforcing
 * an initial-install baseline and safe holding state for unverified media.
 */
object InstallRecoveryManager {
    private const val TAG = "InstallRecoveryManager"

    const val KEY_BASELINE_COMPLETED = "initial_baseline_completed"
    const val KEY_BASELINE_TIMESTAMP = "initial_baseline_timestamp"
    const val KEY_BASELINE_MEDIA_COUNT = "initial_baseline_media_count"

    enum class BaselineState {
        EXISTING_HISTORY_VALID,    // Database already has remotePhotos or completed upload history
        FRESH_BASELINE_ESTABLISHED,// Fresh baseline established; existing media placed in HOLDING
        ONGOING_NORMAL,            // Baseline was previously completed; normal scanning active
        PENDING_INITIAL_SCAN       // Database is fresh/empty; awaiting initial MediaStore scan
    }

    /**
     * Determines whether the initial baseline has been established.
     * Uses Room sync_metadata as authoritative DB record with fallback to Preferences.
     */
    suspend fun isBaselineCompleted(database: WhDatabase = DbHolder.database): Boolean = withContext(Dispatchers.IO) {
        val dbFlag = database.syncMetadataDao().get(KEY_BASELINE_COMPLETED)
        if (dbFlag == "true") return@withContext true

        Preferences.isInitialBaselineCompleted()
    }

    /**
     * Returns the timestamp (ms) when the initial baseline was completed.
     */
    suspend fun getBaselineTimestamp(database: WhDatabase = DbHolder.database): Long = withContext(Dispatchers.IO) {
        val dbTs = database.syncMetadataDao().get(KEY_BASELINE_TIMESTAMP)?.toLongOrNull()
        if (dbTs != null && dbTs > 0L) return@withContext dbTs
        Preferences.getInitialBaselineTimestamp()
    }

    /**
     * Inspects the database on app startup or before scanning.
     * If the database is missing historical records (e.g. fresh install or reinstall),
     * establishes the protective baseline and holds unverified items safely.
     */
    suspend fun checkAndInitializeBaseline(
        context: Context,
        database: WhDatabase = DbHolder.database
    ): BaselineState = withContext(Dispatchers.IO) {
        val photoDao = database.photoDao()
        val remotePhotoDao = database.remotePhotoDao()

        // 1. If baseline was already marked completed, we're in normal ongoing mode
        if (isBaselineCompleted(database)) {
            Log.d(TAG, "Baseline already completed previously. Normal ongoing mode.")
            return@withContext BaselineState.ONGOING_NORMAL
        }

        // 2. Check if the database has existing valid remote/upload history
        val remoteCount = remotePhotoDao.getCount()
        if (remoteCount > 0) {
            Log.i(TAG, "Database contains $remoteCount valid remote records. Preserving existing upload history.")
            markBaselineCompleted(database, remoteCount)
            return@withContext BaselineState.EXISTING_HISTORY_VALID
        }

        // 3. Database is fresh / missing historical records (reinstall or first launch)
        // If there are unverified 'NONE' photos already in DB, transition them to 'HOLDING'
        val unverifiedCount = photoDao.getUnverifiedNoneCount()
        if (unverifiedCount > 0) {
            val protectedCount = photoDao.markAllUnverifiedAsHolding()
            Log.w(TAG, "REINSTALL PROTECTION ACTIVE: Moved $protectedCount existing unverified items from 'NONE' to 'HOLDING'. Bulk upload prevented.")
            markBaselineCompleted(database, protectedCount)
            Log.i(TAG, "Initial baseline established successfully. Protected media: $protectedCount")
            return@withContext BaselineState.FRESH_BASELINE_ESTABLISHED
        }

        // If the database has no photos at all, do NOT prematurely mark baseline completed!
        // SyncDbMediaStoreWorker will stream the initial scan, place media in HOLDING,
        // and mark baseline completed upon finishing.
        val totalPhotos = photoDao.getCount()
        if (totalPhotos == 0) {
            Log.i(TAG, "Database is completely empty. Awaiting initial MediaStore scan before completing baseline.")
            return@withContext BaselineState.PENDING_INITIAL_SCAN
        }

        // All existing photos are already categorized (e.g. HOLDING or DONE)
        markBaselineCompleted(database, totalPhotos)
        Log.i(TAG, "Initial baseline completed with $totalPhotos existing records.")
        BaselineState.FRESH_BASELINE_ESTABLISHED
    }

    /**
     * Marks initial baseline as completed in both sync_metadata (Room) and Preferences.
     */
    suspend fun markBaselineCompleted(
        database: WhDatabase = DbHolder.database,
        mediaCount: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val syncMetadataDao = database.syncMetadataDao()
        syncMetadataDao.put(SyncMetadata(KEY_BASELINE_COMPLETED, "true"))
        syncMetadataDao.put(SyncMetadata(KEY_BASELINE_TIMESTAMP, timestamp.toString()))
        syncMetadataDao.put(SyncMetadata(KEY_BASELINE_MEDIA_COUNT, mediaCount.toString()))

        Preferences.setInitialBaselineCompleted(true)
        Preferences.setInitialBaselineTimestamp(timestamp)
        Preferences.setInitialBaselineMediaCount(mediaCount.toLong())
    }

    /**
     * Reconciles unverified local media (in HOLDING or NONE state) with remote records
     * by matching content SHA-256 or local IDs.
     * Transitions matched media to Photo.STATUS_DONE with the verified remoteId.
     *
     * @return Number of local media items reconciled.
     */
    suspend fun reconcileLocalPhotosWithRemoteHistory(
        context: Context,
        database: WhDatabase = DbHolder.database
    ): Int = withContext(Dispatchers.IO) {
        val photoDao = database.photoDao()
        val remotePhotoDao = database.remotePhotoDao()

        val allRemote = remotePhotoDao.getAll()
        if (allRemote.isEmpty()) return@withContext 0

        val remoteByHash = allRemote.filter { !it.contentHash.isNullOrBlank() }.associateBy { it.contentHash!! }
        val remoteByLocalId = allRemote.filter { !it.localId.isNullOrBlank() }.associateBy { it.localId!! }

        val unverifiedPhotos = photoDao.getAll().filter { it.remoteId == null }
        var matchedCount = 0

        for (photo in unverifiedPhotos) {
            // 1. Match by content hash if available
            var hash = photo.contentHash
            if (hash == null) {
                hash = runCatching { ContentHasher.computeHash(context, photo.pathUri.toUri()) }.getOrNull()
                if (hash != null) {
                    photoDao.updateContentHash(photo.localId, hash)
                }
            }

            var matchingRemote: RemotePhoto? = null
            if (hash != null) {
                matchingRemote = remoteByHash[hash]
            }

            // 2. Match by localId if from same device
            if (matchingRemote == null) {
                matchingRemote = remoteByLocalId[photo.localId]
            }

            if (matchingRemote != null) {
                photoDao.updateRemoteIdForLocalId(photo.localId, matchingRemote.remoteId)
                photoDao.updateUploadStatus(photo.localId, Photo.STATUS_DONE, matchingRemote.uploadedAt)
                matchedCount++
                Log.d(TAG, "Reconciled local photo ${photo.localId} with remoteId ${matchingRemote.remoteId} (hash=$hash)")
            }
        }

        Log.i(TAG, "Reconciliation finished: $matchedCount local media items reconciled with remote records.")
        matchedCount
    }
}
