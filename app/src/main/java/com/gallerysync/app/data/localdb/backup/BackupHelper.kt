package com.gallerysync.app.data.localdb.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gallerysync.app.R
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.api.TelegramChunkApi
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.utils.toastFromMainThread
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.documentfile.provider.DocumentFile

object BackupHelper {
    const val JSON_MIME = "application/json"
    private const val TAG = "BackupHelper"
    private const val DATABASE_BACKUP_PREFIX = "GallerySync_Backup"

    private val mapper by lazy {
        ObjectMapper().apply {
            // Configure to ignore unknown fields during deserialization
            // This makes import robust against schema changes
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    suspend fun exportDatabase(uri: Uri, context: Context) {
        try {
            val deviceId = Preferences.getOrCreateDeviceId()
            val deviceName = Preferences.getDeviceName()
            val channelId = Preferences.getEncryptedChannelId()
            val botUsername = Preferences.getBotUsername()
            val photos = DbHolder.database.photoDao().getAll()
            val remotePhotos = DbHolder.database.remotePhotoDao().getAllIncludingDeleted()

            val mediaRecords = remotePhotos.map { remote ->
                val linkedLocal = photos.find { it.remoteId == remote.remoteId || (remote.contentHash != null && it.contentHash == remote.contentHash) }
                ExportedMediaRecord(
                    originalMediaId = remote.localId ?: linkedLocal?.localId ?: remote.remoteId,
                    originalUri = linkedLocal?.pathUri,
                    displayName = remote.fileName,
                    fileSize = remote.fileSize,
                    mediaType = remote.mediaType,
                    modificationTimestamp = linkedLocal?.lastUploadAttempt,
                    contentSha256 = remote.contentHash ?: linkedLocal?.contentHash,
                    telegramFileId = remote.remoteId,
                    telegramMessageId = remote.messageId,
                    destinationChannelId = channelId.takeIf { it != 0L },
                    botIdentityId = botUsername.takeIf { it.isNotBlank() },
                    backupCompletionState = remote.status,
                    backupTimestamp = remote.uploadedAt
                )
            }

            val catalogEntity = if (channelId != 0L) {
                DbHolder.database.backupCatalogDao().getByChannelId(channelId)
            } else null

            val chunkedBackups = DbHolder.database.chunkedBackupDao().getAll()
            val manifests = chunkedBackups.filter { !it.manifestFileId.isNullOrBlank() }.map { cb ->
                ManifestReference(
                    backupId = cb.backupId,
                    fileId = cb.manifestFileId!!,
                    messageId = cb.manifestMessageId ?: 0L,
                    fileName = "gallerysync_manifest_${cb.backupId}.json",
                    sizeBytes = cb.fileSize,
                    sha256 = cb.fileSha256 ?: "",
                    mediaSha256 = cb.fileSha256 ?: "",
                    displayName = cb.fileName
                )
            }

            val backupFile = BackupFile(
                version = 4,
                deviceId = deviceId,
                deviceName = deviceName,
                createdAt = System.currentTimeMillis(),
                channelId = channelId.takeIf { it != 0L },
                botUsername = botUsername.takeIf { it.isNotBlank() },
                catalogGeneration = catalogEntity?.lastConfirmedGeneration,
                masterIndexFileId = catalogEntity?.indexFileId,
                masterIndexSha256 = catalogEntity?.indexSha256,
                pinnedMessageId = catalogEntity?.pinnedMessageId,
                manifests = manifests,
                photos = photos,
                remotePhotos = remotePhotos,
                mediaRecords = mediaRecords
            )
            
            // Handle both specific file URIs and directory (tree) URIs
            val targetUri = if (uri.toString().contains("tree")) {
                val directory = DocumentFile.fromTreeUri(context, uri)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_hh-mm-a", Locale.getDefault())
                val timestamp = dateFormat.format(Date())
                val fileName = "GallerySync_Backup_${deviceId}_$timestamp.json"
                directory?.createFile(JSON_MIME, fileName)?.uri
            } else {
                uri
            }

            if (targetUri == null) {
                Log.e(TAG, "Failed to resolve target URI for export")
                return
            }

            context.contentResolver.openOutputStream(targetUri, "wt")?.use {
                val favoritesJson = mapper.writeValueAsBytes(backupFile)
                it.write(favoritesJson)
            }
            context.toastFromMainThread(context.getString(R.string.export_successful))
        } catch (e: Exception) {
            context.toastFromMainThread(e.localizedMessage)
            Log.d("Export All Photos", "doWork: ${e.localizedMessage}")
        }
    }

    /**
     * Import database backup with device-aware merge:
     * - Same device: full import (photos + remotePhotos)
     * - Different device: only merge remotePhotos (skip photos to avoid localId collisions)
     */
    suspend fun importDatabase(uri: Uri, context: Context) {
        try {
            context.contentResolver.openInputStream(uri)?.use {
                val backupFile = mapper.readValue(it.readBytes(), BackupFile::class.java)
                val currentDeviceId = Preferences.getOrCreateDeviceId()
                val isSameDevice = backupFile.deviceId.isEmpty() || backupFile.deviceId == currentDeviceId

                // Destination identity validation (Section 3 & 6)
                val configuredChannel = Preferences.getEncryptedChannelId()
                if (backupFile.channelId != null && configuredChannel != 0L && backupFile.channelId != configuredChannel) {
                    Log.w(TAG, "Destination identity warning: Backup destination channel (${backupFile.channelId}) differs from active channel ($configuredChannel). Media remains valid.")
                }
                val configuredBot = Preferences.getBotUsername()
                if (!backupFile.botUsername.isNullOrBlank() && configuredBot.isNotBlank() && backupFile.botUsername != configuredBot) {
                    Log.w(TAG, "Destination identity warning: Backup bot (${backupFile.botUsername}) differs from active bot ($configuredBot).")
                }

                if (isSameDevice) {
                    // Same device — full import
                    Log.i(TAG, "Importing from same device (${backupFile.deviceId}): full import")
                    DbHolder.database.photoDao().insertPhotos(*backupFile.photos.toTypedArray())
                    DbHolder.database.remotePhotoDao().insertAllIfNotExists(
                        *backupFile.remotePhotos.toTypedArray()
                    )
                } else {
                    // Different device — only merge remotePhotos to avoid localId collisions
                    Log.i(TAG, "Importing from different device (${backupFile.deviceId} → $currentDeviceId): remotePhotos only")
                    DbHolder.database.remotePhotoDao().insertAllIfNotExists(
                        *backupFile.remotePhotos.toTypedArray()
                    )
                }

                // If mediaRecords are present, ensure any missing records are merged into remote_photos
                if (backupFile.mediaRecords.isNotEmpty()) {
                    val existingRemoteIds = DbHolder.database.remotePhotoDao().getAllRemoteIds().toSet()
                    val additionalRemotePhotos = backupFile.mediaRecords.filter { it.telegramFileId != null && it.telegramFileId !in existingRemoteIds }.map { rec ->
                        com.gallerysync.app.data.localdb.entities.RemotePhoto(
                            remoteId = rec.telegramFileId!!,
                            photoType = rec.displayName?.substringAfterLast('.', "")?.ifEmpty { if (rec.mediaType == "VIDEO") "mp4" else "jpg" } ?: "jpg",
                            fileName = rec.displayName,
                            fileSize = rec.fileSize,
                            uploadedAt = rec.backupTimestamp,
                            messageId = rec.telegramMessageId,
                            localId = rec.originalMediaId,
                            contentHash = rec.contentSha256,
                            status = if (rec.backupCompletionState == "DELETED") "DELETED" else "ACTIVE",
                            mediaType = rec.mediaType
                        )
                    }
                    if (additionalRemotePhotos.isNotEmpty()) {
                        DbHolder.database.remotePhotoDao().insertAllIfNotExists(*additionalRemotePhotos.toTypedArray())
                    }
                }

                // Reconcile unverified/holding local photos with imported remote evidence
                val reconciledCount = com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.reconcileLocalPhotosWithRemoteHistory(context)
                Log.i(TAG, "Reconciled $reconciledCount local media items with imported backup metadata.")

                // Restore catalog state if present in backup
                if (backupFile.catalogGeneration != null && backupFile.masterIndexFileId != null && backupFile.channelId != null) {
                    val botToken = Preferences.getEncryptedString(Preferences.botToken, "")
                    val catalogEntity = BackupCatalogEntity(
                        channelId = backupFile.channelId,
                        botId = TelegramChunkApi.extractBotId(botToken),
                        lastConfirmedGeneration = backupFile.catalogGeneration,
                        pinnedMessageId = backupFile.pinnedMessageId ?: 0L,
                        indexFileId = backupFile.masterIndexFileId,
                        indexSha256 = backupFile.masterIndexSha256 ?: "",
                        updatedAt = backupFile.createdAt,
                        syncStatus = "COMMITTED"
                    )
                    DbHolder.database.backupCatalogDao().insertOrUpdate(catalogEntity)
                    Log.i(TAG, "Restored catalog state: Generation ${backupFile.catalogGeneration}, indexFileId=${backupFile.masterIndexFileId}")
                }
            }
            context.toastFromMainThread(context.getString(R.string.import_successful))
        } catch (e: Exception) {
            Log.d("Import All Photos", "doWork: ${e.localizedMessage}")
            context.toastFromMainThread(e.localizedMessage)
        }
    }

    /**
     * Upload database backup to Telegram with device metadata
     */
    suspend fun uploadDatabaseToTelegram(context: Context): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== UPLOADING DATABASE TO TELEGRAM ===")

                val channelId = Preferences.getEncryptedLong(Preferences.channelId, 0L)
                if (channelId == 0L) {
                    return@withContext Result.failure(Exception("No Telegram channel configured"))
                }

                val deviceId = Preferences.getOrCreateDeviceId()
                val deviceName = Preferences.getDeviceName()
                val botUsername = Preferences.getBotUsername()

                val photos = DbHolder.database.photoDao().getAll()
                val remotePhotos = DbHolder.database.remotePhotoDao().getAllIncludingDeleted()

                val mediaRecords = remotePhotos.map { remote ->
                    val linkedLocal = photos.find { it.remoteId == remote.remoteId || (remote.contentHash != null && it.contentHash == remote.contentHash) }
                    ExportedMediaRecord(
                        originalMediaId = remote.localId ?: linkedLocal?.localId ?: remote.remoteId,
                        originalUri = linkedLocal?.pathUri,
                        displayName = remote.fileName,
                        fileSize = remote.fileSize,
                        mediaType = remote.mediaType,
                        modificationTimestamp = linkedLocal?.lastUploadAttempt,
                        contentSha256 = remote.contentHash ?: linkedLocal?.contentHash,
                        telegramFileId = remote.remoteId,
                        telegramMessageId = remote.messageId,
                        destinationChannelId = channelId.takeIf { it != 0L },
                        botIdentityId = botUsername.takeIf { it.isNotBlank() },
                        backupCompletionState = remote.status,
                        backupTimestamp = remote.uploadedAt
                    )
                }

                val catalogEntity = if (channelId != 0L) {
                    DbHolder.database.backupCatalogDao().getByChannelId(channelId)
                } else null

                val chunkedBackups = DbHolder.database.chunkedBackupDao().getAll()
                val manifests = chunkedBackups.filter { !it.manifestFileId.isNullOrBlank() }.map { cb ->
                    ManifestReference(
                        backupId = cb.backupId,
                        fileId = cb.manifestFileId!!,
                        messageId = cb.manifestMessageId ?: 0L,
                        fileName = "gallerysync_manifest_${cb.backupId}.json",
                        sizeBytes = cb.fileSize,
                        sha256 = cb.fileSha256 ?: "",
                        mediaSha256 = cb.fileSha256 ?: "",
                        displayName = cb.fileName
                    )
                }

                val backupFile = BackupFile(
                    version = 4,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    createdAt = System.currentTimeMillis(),
                    channelId = channelId.takeIf { it != 0L },
                    botUsername = botUsername.takeIf { it.isNotBlank() },
                    catalogGeneration = catalogEntity?.lastConfirmedGeneration,
                    masterIndexFileId = catalogEntity?.indexFileId,
                    masterIndexSha256 = catalogEntity?.indexSha256,
                    pinnedMessageId = catalogEntity?.pinnedMessageId,
                    manifests = manifests,
                    photos = photos,
                    remotePhotos = remotePhotos,
                    mediaRecords = mediaRecords
                )

                Log.i(TAG, "Database backup created: ${photos.size} photos, ${remotePhotos.size} remote photos (device: $deviceId)")

                val backupJson = mapper.writeValueAsBytes(backupFile)

                val dateFormat = SimpleDateFormat("yyyy-MM-dd_hh-mm-a", Locale.getDefault())
                val timestamp = dateFormat.format(Date())
                val fileName = "${DATABASE_BACKUP_PREFIX}_${deviceId}_$timestamp.json"

                val tempFile = File(context.cacheDir, fileName)
                tempFile.writeBytes(backupJson)

                Log.i(TAG, "Created backup file: $fileName (${tempFile.length()} bytes)")

                // Upload with device tag in caption
                val caption = "#db_backup #device:$deviceId $deviceName"
                Log.i(TAG, "Uploading to Telegram channel: $channelId")
                val uploadResult = BotApi.sendFile(tempFile, channelId, caption)

                val (response, error) = uploadResult
                if (error != null) {
                    Log.e(TAG, "❌ Failed to upload database backup", error)
                    tempFile.delete()
                    Result.failure(error)
                } else {
                    val document = response?.body()?.result?.document
                    if (document != null) {
                        Log.i(TAG, "✅ Database backup uploaded successfully: ${document.fileId}")

                        Preferences.edit {
                            putString("last_database_backup_file_id", document.fileId)
                            putString("last_database_backup_filename", fileName)
                            putLong("last_database_backup_timestamp", System.currentTimeMillis())
                            putLong("last_database_backup_photos", photos.size.toLong())
                            putLong("last_database_backup_remote_photos", remotePhotos.size.toLong())
                        }

                        tempFile.delete()
                        Result.success("Database uploaded to Telegram: $fileName")
                    } else {
                        tempFile.delete()
                        Result.failure(Exception("Upload failed: No document in response"))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception uploading database to Telegram", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Download and import database backup from Telegram with device-aware merge
     */
    suspend fun downloadDatabaseFromTelegram(fileId: String, context: Context): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== DOWNLOADING DATABASE FROM TELEGRAM ===")
                Log.i(TAG, "Downloading backup file: $fileId")

                val fileBytes = BotApi.getFile(fileId)
                if (fileBytes == null) {
                    return@withContext Result.failure(Exception("Failed to download backup file"))
                }

                val backupFile = mapper.readValue(fileBytes, BackupFile::class.java)
                val currentDeviceId = Preferences.getOrCreateDeviceId()
                val isSameDevice = backupFile.deviceId.isEmpty() || backupFile.deviceId == currentDeviceId

                Log.i(TAG, "Database backup downloaded: ${backupFile.photos.size} photos, ${backupFile.remotePhotos.size} remote photos (from device: ${backupFile.deviceId})")

                if (isSameDevice) {
                    Log.i(TAG, "Importing from same device (${backupFile.deviceId}): full import")
                    DbHolder.database.photoDao().insertPhotos(*backupFile.photos.toTypedArray())
                    DbHolder.database.remotePhotoDao().insertAllIfNotExists(*backupFile.remotePhotos.toTypedArray())
                } else {
                    Log.i(TAG, "Importing from different device (${backupFile.deviceId} → $currentDeviceId): remotePhotos only")
                    DbHolder.database.remotePhotoDao().insertAllIfNotExists(*backupFile.remotePhotos.toTypedArray())
                }

                // If mediaRecords are present, ensure any missing records are merged into remote_photos
                if (backupFile.mediaRecords.isNotEmpty()) {
                    val existingRemoteIds = DbHolder.database.remotePhotoDao().getAllRemoteIds().toSet()
                    val additionalRemotePhotos = backupFile.mediaRecords.filter { it.telegramFileId != null && it.telegramFileId !in existingRemoteIds }.map { rec ->
                        com.gallerysync.app.data.localdb.entities.RemotePhoto(
                            remoteId = rec.telegramFileId!!,
                            photoType = rec.displayName?.substringAfterLast('.', "")?.ifEmpty { if (rec.mediaType == "VIDEO") "mp4" else "jpg" } ?: "jpg",
                            fileName = rec.displayName,
                            fileSize = rec.fileSize,
                            uploadedAt = rec.backupTimestamp,
                            messageId = rec.telegramMessageId,
                            localId = rec.originalMediaId,
                            contentHash = rec.contentSha256,
                            status = if (rec.backupCompletionState == "DELETED") "DELETED" else "ACTIVE",
                            mediaType = rec.mediaType
                        )
                    }
                    if (additionalRemotePhotos.isNotEmpty()) {
                        DbHolder.database.remotePhotoDao().insertAllIfNotExists(*additionalRemotePhotos.toTypedArray())
                    }
                }

                // Reconcile unverified/holding local photos with imported remote evidence
                com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager.reconcileLocalPhotosWithRemoteHistory(context)

                Log.i(TAG, "Database imported successfully")

                Preferences.edit {
                    putLong("last_database_import_timestamp", System.currentTimeMillis())
                    putLong("last_database_import_photos", backupFile.photos.size.toLong())
                    putLong("last_database_import_remote_photos", backupFile.remotePhotos.size.toLong())
                }

                val importType = if (isSameDevice) "full" else "remotePhotos only"
                Result.success("Database imported ($importType): ${backupFile.photos.size} photos, ${backupFile.remotePhotos.size} remote photos")

            } catch (e: Exception) {
                Log.e(TAG, "Exception downloading database from Telegram", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Check if database backup is up to date
     */
    suspend fun isDatabaseBackupUpToDate(
        currentPhotos: Int? = null,
        currentRemotePhotos: Int? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val lastBackupTimestamp = Preferences.getLong("last_database_backup_timestamp", 0L)
                val lastBackupPhotos = Preferences.getLong("last_database_backup_photos", 0L).toInt()
                val lastBackupRemotePhotos = Preferences.getLong("last_database_backup_remote_photos", 0L).toInt()

                val photosCount = currentPhotos ?: DbHolder.database.photoDao().getCount()
                val remotePhotosCount = currentRemotePhotos ?: DbHolder.database.remotePhotoDao().getCount()

                val hasBackup = lastBackupTimestamp > 0
                val dataUnchanged = (photosCount == lastBackupPhotos && remotePhotosCount == lastBackupRemotePhotos)

                Log.d(TAG, "Backup status - Has backup: $hasBackup, Data unchanged: $dataUnchanged")
                Log.d(TAG, "Current: $photosCount photos, $remotePhotosCount remote | Last backup: $lastBackupPhotos photos, $lastBackupRemotePhotos remote")

                hasBackup && dataUnchanged

            } catch (e: Exception) {
                Log.e(TAG, "Error checking backup status", e)
                false
            }
        }
    }

    fun shouldCreateDailyBackup(): Boolean {
        val lastBackup = Preferences.getLong("last_database_backup_timestamp", 0L)
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        return lastBackup < oneDayAgo
    }

    suspend fun getBackupStats(): BackupStats {
        return withContext(Dispatchers.IO) {
            val lastBackupTime = Preferences.getLong("last_database_backup_timestamp", 0L)
            val lastBackupFilename = Preferences.getString("last_database_backup_filename", "")
            val lastImportTime = Preferences.getLong("last_database_import_timestamp", 0L)
            
            val currentPhotos = DbHolder.database.photoDao().getCount()
            val currentRemotePhotos = DbHolder.database.remotePhotoDao().getCount()
            
            val isUpToDate = isDatabaseBackupUpToDate(currentPhotos, currentRemotePhotos)

            BackupStats(
                lastBackupTime = lastBackupTime,
                lastBackupFilename = lastBackupFilename,
                lastImportTime = lastImportTime,
                currentPhotos = currentPhotos,
                currentRemotePhotos = currentRemotePhotos,
                isUpToDate = isUpToDate,
                needsDailyBackup = shouldCreateDailyBackup()
            )
        }
    }

    data class BackupStats(
        val lastBackupTime: Long,
        val lastBackupFilename: String,
        val lastImportTime: Long,
        val currentPhotos: Int,
        val currentRemotePhotos: Int,
        val isUpToDate: Boolean,
        val needsDailyBackup: Boolean
    )
}
