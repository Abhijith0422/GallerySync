package com.gallerysync.app.data.localdb.recovery

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramChunkApi
import com.gallerysync.app.data.catalog.MasterIndexManager
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.SyncMetadata
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class TelegramCatalogRecoveryResult {
    data class Success(
        val generation: Long,
        val totalMediaCount: Int,
        val manifestsRecovered: Int,
        val mediaRecordsRecovered: Int,
        val localReconciledCount: Int
    ) : TelegramCatalogRecoveryResult()

    object NoCatalogFound : TelegramCatalogRecoveryResult()
    data class IdentityMismatch(val reason: String) : TelegramCatalogRecoveryResult()
    data class Error(val message: String, val cause: Throwable? = null) : TelegramCatalogRecoveryResult()
}

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

    /**
     * Recovers backup catalog metadata directly from the pinned Telegram Master Index.
     * Reconstructs RemotePhoto records, ChunkedBackup manifests, chunks, and reconciles
     * local MediaStore photos without downloading original media chunks.
     */
    suspend fun recoverCatalogFromTelegram(
        context: Context,
        botToken: String,
        channelId: Long,
        database: WhDatabase = DbHolder.database,
        catalogApi: TelegramCatalogApi = TelegramCatalogApi.defaultInstance,
        indexManager: MasterIndexManager = MasterIndexManager.defaultInstance
    ): TelegramCatalogRecoveryResult = withContext(Dispatchers.IO) {
        val botId = TelegramChunkApi.extractBotId(botToken)
        if (botId <= 0L) {
            return@withContext TelegramCatalogRecoveryResult.Error("Invalid botToken format")
        }

        // 1. Query Telegram channel getChat for pinned message
        val chatInfoResult = catalogApi.getChatInfo(botToken, channelId)
        val chatInfo = when (chatInfoResult) {
            is TelegramCatalogResult.Success -> chatInfoResult.value
            is TelegramCatalogResult.RateLimited -> return@withContext TelegramCatalogRecoveryResult.Error("Rate limited by Telegram (${chatInfoResult.retryAfterSeconds}s)")
            is TelegramCatalogResult.Unauthorized -> return@withContext TelegramCatalogRecoveryResult.Error("Unauthorized bot credentials")
            is TelegramCatalogResult.PermissionDenied -> return@withContext TelegramCatalogRecoveryResult.Error("Bot lacks permissions in channel $channelId")
            else -> return@withContext TelegramCatalogRecoveryResult.Error("Failed to fetch chat info: ${(chatInfoResult as? TelegramCatalogResult.InvalidResponse)?.reason}")
        }

        val pinnedMsg = chatInfo.pinnedMessage
            ?: return@withContext TelegramCatalogRecoveryResult.NoCatalogFound
        val doc = pinnedMsg.document
            ?: return@withContext TelegramCatalogRecoveryResult.NoCatalogFound

        val fileName = doc.fileName ?: ""
        if (!fileName.contains("master_index", ignoreCase = true)) {
            Log.w(TAG, "Pinned document $fileName is not a GallerySync master index")
            return@withContext TelegramCatalogRecoveryResult.NoCatalogFound
        }

        // 2. Download master index JSON bytes
        val indexBytesResult = catalogApi.downloadFileByFileId(botToken, doc.fileId)
        val indexBytes = when (indexBytesResult) {
            is TelegramCatalogResult.Success -> indexBytesResult.value
            else -> return@withContext TelegramCatalogRecoveryResult.Error("Failed to download master index file: ${doc.fileId}")
        }

        val mapper = ObjectMapper().apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
        val masterIndex = try {
            val idx = mapper.readValue(indexBytes, MasterIndex::class.java)
            idx.validate()
            idx
        } catch (e: Exception) {
            return@withContext TelegramCatalogRecoveryResult.Error("Malformed master index JSON: ${e.message}", e)
        }

        // 3. Validate channel and bot identities
        if (masterIndex.channelId != channelId) {
            return@withContext TelegramCatalogRecoveryResult.IdentityMismatch(
                "Master index channel ID (${masterIndex.channelId}) does not match target channel ($channelId)"
            )
        }
        if (masterIndex.botId != botId) {
            return@withContext TelegramCatalogRecoveryResult.IdentityMismatch(
                "Master index bot ID (${masterIndex.botId}) does not match current bot ID ($botId)"
            )
        }

        Log.i(TAG, "Recovering catalog from Telegram: Generation ${masterIndex.generation}, ${masterIndex.totalMediaCount} media items, ${masterIndex.totalManifestCount} manifests")

        val remotePhotoDao = database.remotePhotoDao()
        val chunkedBackupDao = database.chunkedBackupDao()
        val videoChunkDao = database.videoChunkDao()
        val catalogDao = database.backupCatalogDao()

        // 4. Download and recover chunked backup manifests
        var manifestsRecovered = 0
        for (manifestRef in masterIndex.manifests) {
            try {
                val manifestBytes = catalogApi.downloadFileByFileId(botToken, manifestRef.fileId).getOrNull()
                if (manifestBytes != null) {
                    val manifest = mapper.readValue(manifestBytes, BackupManifest::class.java)
                    manifest.validate()

                    val chunkedBackup = ChunkedBackup(
                        backupId = manifest.backupId,
                        localId = manifest.backupId,
                        pathUri = "",
                        fileName = manifest.media.originalName,
                        fileSize = manifest.media.sizeBytes,
                        mimeType = manifest.media.mimeType,
                        width = manifest.media.width,
                        height = manifest.media.height,
                        duration = manifest.media.durationMs,
                        fileSha256 = manifest.media.sha256,
                        chunkSize = manifest.chunkConfiguration.chunkSizeBytes,
                        totalChunks = manifest.chunkConfiguration.totalChunks,
                        completedChunks = manifest.chunks.size,
                        status = ChunkedBackupStatus.DONE,
                        channelId = manifest.destination.chatId,
                        topicId = manifest.destination.topicId,
                        botId = manifest.bot.id,
                        botUsername = manifest.bot.username,
                        manifestFileId = manifestRef.fileId,
                        manifestMessageId = manifestRef.messageId,
                        createdAt = manifest.createdAt,
                        completedAt = manifest.createdAt
                    )
                    chunkedBackupDao.insertOrReplace(chunkedBackup)

                    val chunks = manifest.chunks.map { c ->
                        VideoChunk(
                            backupId = manifest.backupId,
                            chunkIndex = c.index,
                            startOffset = c.startOffset,
                            endOffset = c.endOffsetExclusive,
                            chunkSize = c.sizeBytes,
                            chunkSha256 = c.sha256,
                            status = VideoChunkStatus.DONE,
                            telegramFileId = c.telegramFileId,
                            telegramMessageId = c.telegramMessageId,
                            uploadedAt = manifest.createdAt
                        )
                    }
                    videoChunkDao.insertAll(chunks)

                    // Also register in remote_photos for cross-referencing
                    val remote = RemotePhoto(
                        remoteId = manifestRef.fileId,
                        photoType = "mp4",
                        fileName = manifest.media.originalName,
                        fileSize = manifest.media.sizeBytes,
                        uploadedAt = manifest.createdAt,
                        thumbnailCached = false,
                        localId = manifest.backupId,
                        messageId = manifestRef.messageId,
                        contentHash = manifest.media.sha256,
                        mediaType = "VIDEO",
                        status = "ACTIVE"
                    )
                    remotePhotoDao.insertAllIfNotExists(remote)
                    manifestsRecovered++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover manifest ${manifestRef.backupId}: ${e.message}", e)
            }
        }

        // 5. Download and recover media records (root active records + shards)
        val allMediaRecords = indexManager.fetchAllRecords(botToken, masterIndex)
        val remotePhotosToInsert = allMediaRecords.map { rec ->
            RemotePhoto(
                remoteId = rec.telegramFileId,
                photoType = rec.displayName?.substringAfterLast('.', "")?.ifEmpty { if (rec.mediaType == "VIDEO") "mp4" else "jpg" } ?: "jpg",
                fileName = rec.displayName ?: "media_${rec.telegramFileId}",
                fileSize = rec.sizeBytes,
                uploadedAt = rec.backupTimestamp,
                thumbnailCached = false,
                localId = rec.localId,
                messageId = rec.telegramMessageId,
                contentHash = rec.mediaSha256.takeIf { it.isNotBlank() },
                mediaType = rec.mediaType,
                status = if (rec.backupStatus == "DELETED") "DELETED" else "ACTIVE"
            )
        }

        if (remotePhotosToInsert.isNotEmpty()) {
            remotePhotoDao.insertAllIfNotExists(*remotePhotosToInsert.toTypedArray())
            Log.i(TAG, "Recovered ${remotePhotosToInsert.size} RemotePhoto records from catalog.")
        }

        // 6. Record confirmed generation in backup_catalog
        val catalogEntity = BackupCatalogEntity(
            channelId = channelId,
            botId = botId,
            lastConfirmedGeneration = masterIndex.generation,
            pinnedMessageId = pinnedMsg.messageId,
            indexFileId = doc.fileId,
            indexSha256 = ContentHasher.computeHash(indexBytes),
            updatedAt = masterIndex.updatedAt,
            syncStatus = "COMMITTED"
        )
        catalogDao.insertOrUpdate(catalogEntity)

        // 7. Mark baseline completed since valid historical records were recovered
        markBaselineCompleted(database, remotePhotosToInsert.size + manifestsRecovered)

        // 8. Reconcile local photos with recovered remote history
        val localReconciled = reconcileLocalPhotosWithRemoteHistory(context, database)

        Log.i(TAG, "Catalog recovery complete: Generation ${masterIndex.generation}, $manifestsRecovered manifests, ${remotePhotosToInsert.size} media records recovered, $localReconciled local files reconciled.")

        TelegramCatalogRecoveryResult.Success(
            generation = masterIndex.generation,
            totalMediaCount = masterIndex.totalMediaCount,
            manifestsRecovered = manifestsRecovered,
            mediaRecordsRecovered = remotePhotosToInsert.size,
            localReconciledCount = localReconciled
        )
    }
}
