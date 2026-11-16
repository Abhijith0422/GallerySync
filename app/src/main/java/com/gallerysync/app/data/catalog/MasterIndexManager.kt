package com.gallerysync.app.data.catalog

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramCatalogUploadResult
import com.gallerysync.app.api.TelegramCatalogChatInfo
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.catalog.models.CatalogShard
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.ManifestShard
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.catalog.models.ShardReference
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class MasterIndexReconciliationResult {
    data class Reconciled(val generation: Long, val pinnedMessageId: Long) : MasterIndexReconciliationResult()
    object AlreadyUpToDate : MasterIndexReconciliationResult()
    data class NoRemoteIndex(val reason: String) : MasterIndexReconciliationResult()
    data class Error(val message: String) : MasterIndexReconciliationResult()
}

sealed class MasterIndexCommitResult {
    data class Success(
        val generation: Long,
        val pinnedMessageId: Long,
        val indexFileId: String,
        val totalMediaCount: Int,
        val totalManifestCount: Int
    ) : MasterIndexCommitResult()

    object NoPendingChanges : MasterIndexCommitResult()
    data class RateLimited(val retryAfterSeconds: Int) : MasterIndexCommitResult()
    data class PinVerificationFailed(val reason: String) : MasterIndexCommitResult()
    data class Error(val message: String, val isRetryable: Boolean) : MasterIndexCommitResult()
}

/**
 * Manages the versioned Telegram Master Index:
 * - Aggregates pending media records and chunked backup manifests
 * - Bounds root index size via automatic sharding (1,000 items/shard)
 * - Uploads updated master index JSON to the Telegram backup channel
 * - Pins the new master index and verifies pinning via getChat
 * - Unpins previous master index upon verification
 * - Atomically commits local Room backup_catalog state and pending entries
 */
class MasterIndexManager(
    private val catalogApi: TelegramCatalogApi = TelegramCatalogApi.defaultInstance
) {

    private val mapper by lazy {
        ObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private val commitMutex = Mutex()

    /**
     * Commits all pending catalog entries to Telegram master index.
     * Enforces single-concurrency via Mutex.
     */
    suspend fun commitPendingEntries(
        context: Context,
        botToken: String,
        channelId: Long,
        database: WhDatabase = DbHolder.database,
        forceCommit: Boolean = false
    ): MasterIndexCommitResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val pendingDao = database.pendingCatalogEntryDao()
            val catalogDao = database.backupCatalogDao()

            val botId = TelegramCatalogApi.extractBotId(botToken)
            if (botId <= 0L) {
                return@withContext MasterIndexCommitResult.Error("Invalid botToken format", isRetryable = false)
            }

            // 0. Pre-commit reconciliation: check if remote pinned index is ahead of local catalog (e.g. crash after pin)
            internalReconcileWithRemoteCatalog(botToken, channelId, database)

            val pendingEntries = pendingDao.getPendingEntries()
            if (pendingEntries.isEmpty() && !forceCommit) {
                Log.d(TAG, "No pending catalog entries to commit.")
                return@withContext MasterIndexCommitResult.NoPendingChanges
            }

            Log.i(TAG, "Starting master index commit for channel $channelId (${pendingEntries.size} pending entries)")

            // 1. Resolve base MasterIndex (from Telegram or local confirmed state or fresh)
            val localCatalog = catalogDao.getByChannelId(channelId)
            val baseIndex = fetchOrInitializeBaseIndex(botToken, channelId, localCatalog)

            // 2. Parse pending entries
            val newManifests = mutableListOf<ManifestReference>()
            val newMediaRecords = mutableListOf<CatalogMediaRecord>()
            val processedIds = mutableListOf<Long>()

            for (entry in pendingEntries) {
                try {
                    when (entry.entryType) {
                        PendingCatalogEntry.TYPE_MANIFEST_REFERENCE -> {
                            val ref = mapper.readValue(entry.payloadJson, ManifestReference::class.java)
                            newManifests.add(ref)
                            processedIds.add(entry.id)
                        }
                        PendingCatalogEntry.TYPE_MEDIA_RECORD -> {
                            val record = mapper.readValue(entry.payloadJson, CatalogMediaRecord::class.java)
                            newMediaRecords.add(record)
                            processedIds.add(entry.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Malformed pending entry payload ${entry.id}: ${e.message}", e)
                    // Still mark processed to avoid poison pill
                    processedIds.add(entry.id)
                }
            }

            // 3. Merge manifests (deduplicating by backupId)
            val manifestMap = LinkedHashMap<String, ManifestReference>()
            baseIndex.manifests.forEach { manifestMap[it.backupId] = it }
            newManifests.forEach { manifestMap[it.backupId] = it }
            val mergedManifests = manifestMap.values.toList()

            // 4. Check manifest sharding threshold (1,000 manifests per shard)
            val manifestShardsList = baseIndex.manifestShards.toMutableList()
            var activeManifests = mergedManifests
            val nextGeneration = baseIndex.generation + 1L

            if (activeManifests.size > ManifestShard.MAX_SHARD_MANIFESTS) {
                Log.i(TAG, "Active manifests (${activeManifests.size}) exceed max bound (${ManifestShard.MAX_SHARD_MANIFESTS}). Sharding oldest manifests...")
                val manifestsToShard = activeManifests.take(ManifestShard.MAX_SHARD_MANIFESTS)
                activeManifests = activeManifests.drop(ManifestShard.MAX_SHARD_MANIFESTS)

                val manifestShardId = "manifest_shard_${nextGeneration}_${System.currentTimeMillis()}"
                val manifestShard = ManifestShard(
                    format = ManifestShard.FORMAT_IDENTIFIER,
                    version = ManifestShard.CURRENT_VERSION,
                    shardId = manifestShardId,
                    createdAt = System.currentTimeMillis(),
                    botId = botId,
                    channelId = channelId,
                    itemCount = manifestsToShard.size,
                    manifests = manifestsToShard
                )
                manifestShard.validate()

                val shardBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifestShard)
                val shardSha256 = ContentHasher.computeHash(shardBytes)
                val shardFileName = "gallerysync_manifest_shard_${manifestShardId}.json"

                Log.i(TAG, "Uploading manifest shard $shardFileName (${shardBytes.size} bytes, ${manifestsToShard.size} manifests)...")
                val shardUpload = catalogApi.sendDocument(
                    botToken = botToken,
                    channelId = channelId,
                    fileBytes = shardBytes,
                    fileName = shardFileName,
                    mimeType = "application/json",
                    caption = "GallerySync Manifest Shard $manifestShardId (${manifestsToShard.size} manifests)"
                )

                val shardRef = when (shardUpload) {
                    is TelegramCatalogUploadResult.Success -> {
                        ShardReference(
                            shardId = manifestShardId,
                            fileId = shardUpload.fileId,
                            messageId = shardUpload.messageId,
                            fileName = shardFileName,
                            sizeBytes = shardUpload.fileSize,
                            sha256 = shardSha256,
                            itemCount = manifestsToShard.size
                        )
                    }
                    is TelegramCatalogUploadResult.RateLimited -> {
                        return@withContext MasterIndexCommitResult.RateLimited(shardUpload.retryAfterSeconds)
                    }
                    else -> {
                        val desc = (shardUpload as? TelegramCatalogUploadResult.InvalidResponse)?.reason
                            ?: "Failed to upload manifest shard document"
                        return@withContext MasterIndexCommitResult.Error(desc, isRetryable = true)
                    }
                }
                manifestShardsList.add(shardRef)
                Log.i(TAG, "Manifest Shard $manifestShardId committed: fileId=${shardRef.fileId}, messageId=${shardRef.messageId}")
            }

            // 5. Merge media records (deduplicating by mediaSha256)
            val mediaMap = LinkedHashMap<String, CatalogMediaRecord>()
            baseIndex.activeMediaRecords.forEach {
                val key = if (it.mediaSha256.isNotBlank()) it.mediaSha256 else it.telegramFileId
                mediaMap[key] = it
            }
            newMediaRecords.forEach {
                val key = if (it.mediaSha256.isNotBlank()) it.mediaSha256 else it.telegramFileId
                mediaMap[key] = it
            }
            var allActiveRecords = mediaMap.values.toList()

            // 6. Check sharding threshold (1,000 records per shard)
            val shardsList = baseIndex.shards.toMutableList()

            if (allActiveRecords.size > CatalogShard.MAX_SHARD_RECORDS) {
                Log.i(TAG, "Active media records (${allActiveRecords.size}) exceed max bound (${CatalogShard.MAX_SHARD_RECORDS}). Sharding oldest records...")
                val recordsToShard = allActiveRecords.take(CatalogShard.MAX_SHARD_RECORDS)
                allActiveRecords = allActiveRecords.drop(CatalogShard.MAX_SHARD_RECORDS)

                val shardId = "shard_${nextGeneration}_${System.currentTimeMillis()}"
                val shard = CatalogShard(
                    format = CatalogShard.FORMAT_IDENTIFIER,
                    version = CatalogShard.CURRENT_VERSION,
                    shardId = shardId,
                    createdAt = System.currentTimeMillis(),
                    botId = botId,
                    channelId = channelId,
                    itemCount = recordsToShard.size,
                    records = recordsToShard
                )
                shard.validate()

                val shardBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(shard)
                val shardSha256 = ContentHasher.computeHash(shardBytes)
                val shardFileName = "gallerysync_shard_${shardId}.json"

                Log.i(TAG, "Uploading shard $shardFileName (${shardBytes.size} bytes, ${recordsToShard.size} records)...")
                val shardUpload = catalogApi.sendDocument(
                    botToken = botToken,
                    channelId = channelId,
                    fileBytes = shardBytes,
                    fileName = shardFileName,
                    mimeType = "application/json",
                    caption = "GallerySync Shard $shardId (${recordsToShard.size} items)"
                )

                val shardRef = when (shardUpload) {
                    is TelegramCatalogUploadResult.Success -> {
                        ShardReference(
                            shardId = shardId,
                            fileId = shardUpload.fileId,
                            messageId = shardUpload.messageId,
                            fileName = shardFileName,
                            sizeBytes = shardUpload.fileSize,
                            sha256 = shardSha256,
                            itemCount = recordsToShard.size
                        )
                    }
                    is TelegramCatalogUploadResult.RateLimited -> {
                        return@withContext MasterIndexCommitResult.RateLimited(shardUpload.retryAfterSeconds)
                    }
                    else -> {
                        val desc = (shardUpload as? TelegramCatalogUploadResult.InvalidResponse)?.reason
                            ?: "Failed to upload shard document"
                        return@withContext MasterIndexCommitResult.Error(desc, isRetryable = true)
                    }
                }
                shardsList.add(shardRef)
                Log.i(TAG, "Shard $shardId committed: fileId=${shardRef.fileId}, messageId=${shardRef.messageId}")
            }

            // 7. Build new MasterIndex
            val newMasterIndex = MasterIndex(
                format = MasterIndex.FORMAT_IDENTIFIER,
                version = MasterIndex.CURRENT_VERSION,
                generation = nextGeneration,
                updatedAt = System.currentTimeMillis(),
                botId = botId,
                channelId = channelId,
                manifests = activeManifests,
                manifestShards = manifestShardsList,
                shards = shardsList,
                activeMediaRecords = allActiveRecords,
                totalMediaCount = allActiveRecords.size + shardsList.sumOf { it.itemCount },
                totalManifestCount = activeManifests.size + manifestShardsList.sumOf { it.itemCount }
            )
            newMasterIndex.validate()

            // 7. Serialize master index & compute SHA-256
            val indexBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(newMasterIndex)
            val indexSha256 = ContentHasher.computeHash(indexBytes)
            val indexFileName = MasterIndex.DEFAULT_INDEX_FILE_NAME

            Log.i(TAG, "Uploading Master Index v$nextGeneration (${indexBytes.size} bytes, ${newMasterIndex.totalMediaCount} media items, ${newMasterIndex.totalManifestCount} manifests)...")

            val uploadResult = catalogApi.sendDocument(
                botToken = botToken,
                channelId = channelId,
                fileBytes = indexBytes,
                fileName = indexFileName,
                mimeType = "application/json",
                caption = "GallerySync Master Index v$nextGeneration"
            )

            val (newIndexFileId, newIndexMessageId) = when (uploadResult) {
                is TelegramCatalogUploadResult.Success -> {
                    Pair(uploadResult.fileId, uploadResult.messageId)
                }
                is TelegramCatalogUploadResult.RateLimited -> {
                    return@withContext MasterIndexCommitResult.RateLimited(uploadResult.retryAfterSeconds)
                }
                is TelegramCatalogUploadResult.Unauthorized,
                is TelegramCatalogUploadResult.PermissionDenied -> {
                    val desc = (uploadResult as? TelegramCatalogUploadResult.Unauthorized)?.description
                        ?: (uploadResult as TelegramCatalogUploadResult.PermissionDenied).description
                    return@withContext MasterIndexCommitResult.Error(desc, isRetryable = false)
                }
                else -> {
                    val desc = (uploadResult as? TelegramCatalogUploadResult.InvalidResponse)?.reason
                        ?: "Master index upload failed"
                    return@withContext MasterIndexCommitResult.Error(desc, isRetryable = true)
                }
            }

            // 8. Pin new master index message
            Log.i(TAG, "Pinning new master index message $newIndexMessageId...")
            val pinResult = catalogApi.pinChatMessage(
                botToken = botToken,
                channelId = channelId,
                messageId = newIndexMessageId,
                disableNotification = true
            )

            if (pinResult !is TelegramCatalogResult.Success || !pinResult.value) {
                val err = (pinResult as? TelegramCatalogResult.InvalidResponse)?.reason ?: "Failed to pin master index"
                Log.w(TAG, "Pin request returned unsuccessful: $err")
                return@withContext MasterIndexCommitResult.PinVerificationFailed(err)
            }

            // 9. Verify pinned message in channel via getChat
            Log.i(TAG, "Verifying pinned message via getChat...")
            val chatInfoResult = catalogApi.getChatInfo(botToken, channelId)
            val verifiedChatInfo = when (chatInfoResult) {
                is TelegramCatalogResult.Success -> chatInfoResult.value
                else -> {
                    val desc = (chatInfoResult as? TelegramCatalogResult.InvalidResponse)?.reason ?: "getChat failed"
                    return@withContext MasterIndexCommitResult.PinVerificationFailed("Could not verify pin: $desc")
                }
            }

            val pinnedMsg = verifiedChatInfo.pinnedMessage
            if (pinnedMsg == null || pinnedMsg.messageId != newIndexMessageId) {
                val actualId = pinnedMsg?.messageId
                val err = "Pinned message verification mismatch: expected $newIndexMessageId, got $actualId"
                Log.e(TAG, err)
                return@withContext MasterIndexCommitResult.PinVerificationFailed(err)
            }

            Log.i(TAG, "✅ Verified master index message $newIndexMessageId is currently pinned!")

            // 10. Unpin previous master index message (if different and known)
            val previousPinnedMessageId = localCatalog?.pinnedMessageId
            if (previousPinnedMessageId != null && previousPinnedMessageId > 0L && previousPinnedMessageId != newIndexMessageId) {
                Log.i(TAG, "Unpinning previous master index message $previousPinnedMessageId...")
                catalogApi.unpinChatMessage(botToken, channelId, previousPinnedMessageId)
            }

            // 11. Transactionally record confirmed generation in Room & mark pending committed
            val catalogEntity = BackupCatalogEntity(
                channelId = channelId,
                botId = botId,
                lastConfirmedGeneration = nextGeneration,
                pinnedMessageId = newIndexMessageId,
                indexFileId = newIndexFileId,
                indexSha256 = indexSha256,
                updatedAt = System.currentTimeMillis(),
                syncStatus = "COMMITTED"
            )
            catalogDao.insertOrUpdate(catalogEntity)

            if (processedIds.isNotEmpty()) {
                pendingDao.markCommitted(processedIds)
                Log.i(TAG, "Marked ${processedIds.size} pending catalog entries as COMMITTED.")
            }

            // CRITICAL Phase 10.4.1 Section 2: Commit ChunkedBackup to DONE only AFTER master index confirmation
            val chunkedBackupDao = database.chunkedBackupDao()
            val photoDao = database.photoDao()
            for (manifest in newManifests) {
                chunkedBackupDao.markBackupDone(manifest.backupId, System.currentTimeMillis())
                val cb = chunkedBackupDao.getById(manifest.backupId)
                val targetLocalId = cb?.localId ?: manifest.backupId
                photoDao.updateUploadStatus(targetLocalId, "DONE")
                photoDao.updateRemoteIdForLocalId(targetLocalId, "CHUNKED_${manifest.backupId}")
                Log.i(TAG, "Committed ChunkedBackup ${manifest.backupId} (localId=$targetLocalId) to status DONE.")
            }
            for (record in newMediaRecords) {
                val localId = record.localId
                if (!localId.isNullOrBlank()) {
                    photoDao.updateUploadStatus(localId, "DONE")
                    photoDao.updateRemoteIdForLocalId(localId, record.telegramFileId)
                }
            }

            Log.i(TAG, "🎉 Master index commit completed successfully: Generation $nextGeneration, pinnedMessageId=$newIndexMessageId, indexFileId=$newIndexFileId")

            MasterIndexCommitResult.Success(
                generation = nextGeneration,
                pinnedMessageId = newIndexMessageId,
                indexFileId = newIndexFileId,
                totalMediaCount = newMasterIndex.totalMediaCount,
                totalManifestCount = newMasterIndex.totalManifestCount
            )
        }
    }

    /**
     * Resolves the current base master index.
     * Attempts to download and parse the remote index if pinned, or initializes generation 0.
     */
    private suspend fun fetchOrInitializeBaseIndex(
        botToken: String,
        channelId: Long,
        localCatalog: BackupCatalogEntity?
    ): MasterIndex {
        val botId = TelegramCatalogApi.extractBotId(botToken)

        // Try downloading remote pinned index if available
        val remoteIndex = tryDownloadCurrentPinnedIndex(botToken, channelId)
        if (remoteIndex != null) {
            Log.i(TAG, "Successfully loaded existing remote master index (Generation ${remoteIndex.generation})")
            return remoteIndex
        }

        // If local catalog exists with a fileId, try downloading by fileId
        if (localCatalog?.indexFileId != null && localCatalog.indexFileId.isNotBlank()) {
            val byFileId = tryDownloadIndexByFileId(botToken, localCatalog.indexFileId)
            if (byFileId != null) {
                Log.i(TAG, "Loaded master index from local recorded indexFileId (Generation ${byFileId.generation})")
                return byFileId
            }
        }

        // Fresh initialization
        Log.i(TAG, "No existing master index found. Initializing base generation 0.")
        return MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 0L,
            updatedAt = System.currentTimeMillis(),
            botId = botId,
            channelId = channelId,
            manifests = emptyList(),
            shards = emptyList(),
            activeMediaRecords = emptyList(),
            totalMediaCount = 0,
            totalManifestCount = 0
        )
    }

    /**
     * Attempts to query getChat for the pinned message and download the master index.
     */
    suspend fun tryDownloadCurrentPinnedIndex(
        botToken: String,
        channelId: Long
    ): MasterIndex? {
        val chatInfoResult = catalogApi.getChatInfo(botToken, channelId)
        val chatInfo = chatInfoResult.getOrNull() ?: return null
        val doc = chatInfo.pinnedMessage?.document ?: return null

        val fileName = doc.fileName ?: ""
        if (!fileName.contains("master_index", ignoreCase = true)) {
            Log.w(TAG, "Pinned message document ($fileName) is not a master index")
            return null
        }

        return tryDownloadIndexByFileId(botToken, doc.fileId)
    }

    /**
     * Downloads master index bytes by fileId and deserializes.
     */
    suspend fun tryDownloadIndexByFileId(
        botToken: String,
        fileId: String
    ): MasterIndex? {
        val bytesResult = catalogApi.downloadFileByFileId(botToken, fileId)
        val bytes = bytesResult.getOrNull() ?: return null
        return try {
            val index = mapper.readValue(bytes, MasterIndex::class.java)
            index.validate()
            index
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse downloaded master index: ${e.message}", e)
            null
        }
    }

    /**
     * Downloads all records across root index and shards.
     */
    suspend fun fetchAllRecords(
        botToken: String,
        masterIndex: MasterIndex
    ): List<CatalogMediaRecord> {
        val records = mutableListOf<CatalogMediaRecord>()
        records.addAll(masterIndex.activeMediaRecords)

        for (shardRef in masterIndex.shards) {
            val shardBytes = catalogApi.downloadFileByFileId(botToken, shardRef.fileId).getOrNull()
            if (shardBytes != null) {
                try {
                    val shard = mapper.readValue(shardBytes, CatalogShard::class.java)
                    shard.validate()
                    records.addAll(shard.records)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse shard ${shardRef.shardId}: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Could not download shard ${shardRef.shardId} (fileId=${shardRef.fileId})")
            }
        }
        return records
    }

    /**
     * Downloads all manifests across root index and manifest shards.
     */
    suspend fun fetchAllManifests(
        botToken: String,
        masterIndex: MasterIndex
    ): List<ManifestReference> {
        val manifests = mutableListOf<ManifestReference>()
        manifests.addAll(masterIndex.manifests)

        for (shardRef in masterIndex.manifestShards) {
            val shardBytes = catalogApi.downloadFileByFileId(botToken, shardRef.fileId).getOrNull()
            if (shardBytes != null) {
                try {
                    val shard = mapper.readValue(shardBytes, ManifestShard::class.java)
                    shard.validate()
                    manifests.addAll(shard.manifests)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse manifest shard ${shardRef.shardId}: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Could not download manifest shard ${shardRef.shardId} (fileId=${shardRef.fileId})")
            }
        }
        return manifests
    }

    /**
     * Reconciles local database state with the authoritative Telegram pinned master index.
     * Recovers from crashes during publication stages 2-5 without losing index or duplicating generations.
     */
    suspend fun reconcileWithRemoteCatalog(
        botToken: String,
        channelId: Long,
        database: WhDatabase = DbHolder.database
    ): MasterIndexReconciliationResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            internalReconcileWithRemoteCatalog(botToken, channelId, database)
        }
    }

    private suspend fun internalReconcileWithRemoteCatalog(
        botToken: String,
        channelId: Long,
        database: WhDatabase
    ): MasterIndexReconciliationResult {
        val catalogDao = database.backupCatalogDao()
        val pendingDao = database.pendingCatalogEntryDao()
        val chunkedBackupDao = database.chunkedBackupDao()
        val photoDao = database.photoDao()

        val botId = TelegramCatalogApi.extractBotId(botToken)
        if (botId <= 0L) {
            return MasterIndexReconciliationResult.Error("Invalid botToken format")
        }

        val chatInfoResult = catalogApi.getChatInfo(botToken, channelId)
        val chatInfo = when (chatInfoResult) {
            is TelegramCatalogResult.Success -> chatInfoResult.value
            else -> {
                val desc = (chatInfoResult as? TelegramCatalogResult.InvalidResponse)?.reason ?: "getChat failed"
                return MasterIndexReconciliationResult.Error(desc)
            }
        }

        val pinnedMsg = chatInfo.pinnedMessage
        val doc = pinnedMsg?.document
        if (doc == null || !(doc.fileName ?: "").contains("master_index", ignoreCase = true)) {
            return MasterIndexReconciliationResult.NoRemoteIndex("No pinned master index document found in channel")
        }

        val remoteIndex = tryDownloadIndexByFileId(botToken, doc.fileId)
            ?: return MasterIndexReconciliationResult.Error("Failed to download or parse pinned master index")

        val localCatalog = catalogDao.getByChannelId(channelId)
        val localGen = localCatalog?.lastConfirmedGeneration ?: 0L

        if (remoteIndex.generation <= localGen) {
            return MasterIndexReconciliationResult.AlreadyUpToDate
        }

        Log.i(TAG, "Reconciling local catalog (Gen $localGen) with remote pinned master index (Gen ${remoteIndex.generation})...")

        // Update local backup_catalog
        val catalogEntity = BackupCatalogEntity(
            channelId = channelId,
            botId = botId,
            lastConfirmedGeneration = remoteIndex.generation,
            pinnedMessageId = pinnedMsg.messageId,
            indexFileId = doc.fileId,
            indexSha256 = doc.fileUniqueId ?: "",
            updatedAt = remoteIndex.updatedAt,
            syncStatus = "COMMITTED"
        )
        catalogDao.insertOrUpdate(catalogEntity)

        // Mark matching pending entries committed & chunked backups DONE
        val allManifests = fetchAllManifests(botToken, remoteIndex)
        val manifestBackupIds = allManifests.map { it.backupId }.toSet()
        val pendingEntries = pendingDao.getPendingEntries()
        val processedIds = mutableListOf<Long>()

        for (entry in pendingEntries) {
            if (entry.entryType == PendingCatalogEntry.TYPE_MANIFEST_REFERENCE && entry.referenceId in manifestBackupIds) {
                processedIds.add(entry.id)
            }
        }
        if (processedIds.isNotEmpty()) {
            pendingDao.markCommitted(processedIds)
        }

        for (m in allManifests) {
            chunkedBackupDao.markBackupDone(m.backupId, remoteIndex.updatedAt)
            val cb = chunkedBackupDao.getById(m.backupId)
            val targetLocalId = cb?.localId ?: m.backupId
            photoDao.updateUploadStatus(targetLocalId, "DONE")
            photoDao.updateRemoteIdForLocalId(targetLocalId, "CHUNKED_${m.backupId}")
        }

        Log.i(TAG, "Reconciliation complete: Advanced to Gen ${remoteIndex.generation}, reconciled ${allManifests.size} manifests.")
        return MasterIndexReconciliationResult.Reconciled(remoteIndex.generation, pinnedMsg.messageId)
    }

    companion object {
        private const val TAG = "MasterIndexManager"
        val defaultInstance: MasterIndexManager by lazy { MasterIndexManager() }
    }
}
