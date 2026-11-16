package com.gallerysync.app.data.catalog

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogUploadResult
import com.gallerysync.app.data.catalog.models.ApplicationInfo
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.BotIdentity
import com.gallerysync.app.data.catalog.models.DestinationInfo
import com.gallerysync.app.data.catalog.models.ManifestChunkConfig
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.MediaDescriptor
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ManifestUploadResult {
    data class Success(
        val manifestReference: ManifestReference,
        val manifest: BackupManifest
    ) : ManifestUploadResult()

    data class NotReady(val reason: String) : ManifestUploadResult()
    data class RateLimited(val retryAfterSeconds: Int) : ManifestUploadResult()
    data class Error(val message: String, val isRetryable: Boolean) : ManifestUploadResult()
}

/**
 * Generates and uploads durable JSON manifests for completed chunked video backups.
 * Updates Room chunked_backups and enqueues a PendingCatalogEntry for MasterIndex inclusion.
 */
class BackupManifestManager(
    private val catalogApi: TelegramCatalogApi = TelegramCatalogApi.defaultInstance
) {

    private val mapper by lazy {
        ObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    /**
     * Validates that all chunks are uploaded, builds the [BackupManifest],
     * serializes it to JSON bytes, uploads it to Telegram as a document,
     * updates Room database records, and queues a pending catalog entry.
     */
    suspend fun createAndUploadManifest(
        context: Context,
        backupId: String,
        botToken: String,
        database: WhDatabase = DbHolder.database
    ): ManifestUploadResult = withContext(Dispatchers.IO) {
        val chunkedBackupDao = database.chunkedBackupDao()
        val videoChunkDao = database.videoChunkDao()
        val pendingDao = database.pendingCatalogEntryDao()

        // 1. Fetch chunked backup
        val backup = chunkedBackupDao.getById(backupId)
            ?: return@withContext ManifestUploadResult.Error("Chunked backup $backupId not found", isRetryable = false)

        // 2. Fetch all chunks ordered by index
        val chunks = videoChunkDao.getByBackupId(backupId)
        if (chunks.size != backup.totalChunks) {
            return@withContext ManifestUploadResult.NotReady(
                "Chunk count mismatch: expected ${backup.totalChunks}, found ${chunks.size} in Room"
            )
        }

        // Verify that every single chunk is DONE and has valid telegram identifiers
        val incomplete = chunks.filter { it.status != VideoChunkStatus.DONE || it.telegramFileId.isNullOrBlank() }
        if (incomplete.isNotEmpty()) {
            return@withContext ManifestUploadResult.NotReady(
                "Not all chunks are DONE. Incomplete chunk indices: ${incomplete.map { it.chunkIndex }}"
            )
        }

        // 3. Resolve whole-file SHA-256
        val fileSha256 = backup.fileSha256 ?: run {
            Log.i(TAG, "File SHA-256 missing in backup record $backupId, computing from source...")
            val uri = Uri.parse(backup.pathUri)
            val computed = ContentHasher.computeHash(context, uri)
            if (computed != null) {
                chunkedBackupDao.updateFileSha256(backupId, computed)
                computed
            } else {
                return@withContext ManifestUploadResult.Error(
                    "Unable to compute SHA-256 for source file: ${backup.pathUri}",
                    isRetryable = false
                )
            }
        }

        // 4. Construct BackupManifest
        val manifest = BackupManifest(
            format = BackupManifest.FORMAT_IDENTIFIER,
            version = BackupManifest.CURRENT_VERSION,
            backupId = backup.backupId,
            createdAt = System.currentTimeMillis(),
            application = ApplicationInfo(name = "GallerySync", packageName = "com.gallerysync.app"),
            bot = BotIdentity(id = backup.botId, username = backup.botUsername),
            destination = DestinationInfo(chatId = backup.channelId, topicId = backup.topicId),
            media = MediaDescriptor(
                originalName = backup.fileName,
                mimeType = backup.mimeType,
                sizeBytes = backup.fileSize,
                width = backup.width,
                height = backup.height,
                durationMs = backup.duration,
                sha256 = fileSha256
            ),
            chunkConfiguration = ManifestChunkConfig(
                chunkSizeBytes = backup.chunkSize,
                totalChunks = backup.totalChunks
            ),
            chunks = chunks.map { c ->
                ManifestChunkDescriptor(
                    index = c.chunkIndex,
                    startOffset = c.startOffset,
                    endOffsetExclusive = c.endOffset,
                    sizeBytes = c.chunkSize,
                    sha256 = c.chunkSha256 ?: "",
                    telegramFileId = c.telegramFileId ?: "",
                    telegramMessageId = c.telegramMessageId
                )
            }
        )

        // 5. Strictly validate manifest integrity (throws IllegalStateException on failure)
        try {
            manifest.validate()
        } catch (e: Exception) {
            val err = "Manifest validation failed: ${e.message}"
            Log.e(TAG, err, e)
            return@withContext ManifestUploadResult.Error(err, isRetryable = false)
        }

        // 6. Serialize to JSON bytes & compute manifest file SHA-256
        val manifestJsonBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest)
        val manifestSha256 = ContentHasher.computeHash(manifestJsonBytes)
        val manifestFileName = "gallerysync_manifest_${backup.backupId}.json"

        Log.i(TAG, "Uploading manifest $manifestFileName (${manifestJsonBytes.size} bytes, SHA-256=$manifestSha256)")

        // 7. Upload to Telegram via TelegramCatalogApi
        val uploadResult = catalogApi.sendDocument(
            botToken = botToken,
            channelId = backup.channelId,
            fileBytes = manifestJsonBytes,
            fileName = manifestFileName,
            mimeType = "application/json",
            messageThreadId = backup.topicId,
            caption = "Manifest: ${backup.fileName}"
        )

        when (uploadResult) {
            is TelegramCatalogUploadResult.Success -> {
                Log.i(TAG, "✅ Manifest uploaded successfully: fileId=${uploadResult.fileId}, messageId=${uploadResult.messageId}")

                // 8. Update Room ChunkedBackup record to DONE
                chunkedBackupDao.updateManifestInfo(
                    backupId = backup.backupId,
                    fileId = uploadResult.fileId,
                    messageId = uploadResult.messageId
                )

                // 9. Build ManifestReference & queue in pending_catalog_entries
                val manifestRef = ManifestReference(
                    backupId = backup.backupId,
                    fileId = uploadResult.fileId,
                    messageId = uploadResult.messageId,
                    fileName = manifestFileName,
                    sizeBytes = uploadResult.fileSize,
                    sha256 = manifestSha256,
                    mediaSha256 = fileSha256,
                    displayName = backup.fileName
                )

                val pendingEntry = PendingCatalogEntry(
                    entryType = PendingCatalogEntry.TYPE_MANIFEST_REFERENCE,
                    referenceId = backup.backupId,
                    payloadJson = mapper.writeValueAsString(manifestRef),
                    createdAt = System.currentTimeMillis(),
                    status = PendingCatalogEntry.STATUS_PENDING
                )
                pendingDao.insertOrReplace(pendingEntry)
                Log.i(TAG, "Queued manifest reference in pending_catalog_entries for backupId ${backup.backupId}")

                ManifestUploadResult.Success(manifestRef, manifest)
            }

            is TelegramCatalogUploadResult.RateLimited -> {
                Log.w(TAG, "Rate limited on manifest upload: retry after ${uploadResult.retryAfterSeconds}s")
                ManifestUploadResult.RateLimited(uploadResult.retryAfterSeconds)
            }

            is TelegramCatalogUploadResult.Unauthorized,
            is TelegramCatalogUploadResult.PermissionDenied -> {
                val desc = if (uploadResult is TelegramCatalogUploadResult.Unauthorized) uploadResult.description else (uploadResult as TelegramCatalogUploadResult.PermissionDenied).description
                Log.e(TAG, "Permanent auth error uploading manifest: $desc")
                ManifestUploadResult.Error(desc, isRetryable = false)
            }

            is TelegramCatalogUploadResult.NetworkError,
            is TelegramCatalogUploadResult.ServerError,
            is TelegramCatalogUploadResult.InvalidResponse -> {
                val err = when (uploadResult) {
                    is TelegramCatalogUploadResult.NetworkError -> uploadResult.message
                    is TelegramCatalogUploadResult.ServerError -> uploadResult.description
                    is TelegramCatalogUploadResult.InvalidResponse -> uploadResult.reason
                    else -> "Unknown error"
                }
                Log.w(TAG, "Transient failure uploading manifest: $err")
                ManifestUploadResult.Error(err, isRetryable = true)
            }
        }
    }

    companion object {
        private const val TAG = "BackupManifestManager"
        val defaultInstance: BackupManifestManager by lazy { BackupManifestManager() }
    }
}
