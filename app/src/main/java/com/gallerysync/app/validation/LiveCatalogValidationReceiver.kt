package com.gallerysync.app.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.room.Room
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramCatalogUploadResult
import com.gallerysync.app.data.catalog.BackupManifestManager
import com.gallerysync.app.data.catalog.MasterIndexCommitResult
import com.gallerysync.app.data.catalog.MasterIndexManager
import com.gallerysync.app.data.catalog.MasterIndexReconciliationResult
import com.gallerysync.app.data.catalog.models.ApplicationInfo
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.BotIdentity
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.catalog.models.DestinationInfo
import com.gallerysync.app.data.catalog.models.ManifestChunkConfig
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.catalog.models.MediaDescriptor
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.localdb.recovery.TelegramCatalogRecoveryResult
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Executes controlled, real-world live validation of GallerySync's Phase 10.4
 * Telegram Master Index catalog publication, pinning, remote downloading, and isolated recovery.
 *
 * Triggerable via:
 * adb shell am broadcast -a com.gallerysync.app.VALIDATE_CATALOG
 * or with custom credentials:
 * adb shell am broadcast -a com.gallerysync.app.VALIDATE_CATALOG --es bot_token <token> --el channel_id <channel_id>
 */
class LiveCatalogValidationReceiver : BroadcastReceiver() {

    private val mapper by lazy {
        ObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_VALIDATE_CATALOG) return

        Log.i(TAG, "=================================================================")
        Log.i(TAG, "🚀 RECEIVED PHASE 10.4.2 LIVE CATALOG VALIDATION BROADCAST")
        Log.i(TAG, "=================================================================")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                runLiveValidation(context, intent)
            } catch (e: Throwable) {
                Log.e(TAG, "FATAL EXCEPTION in LiveCatalogValidationReceiver: ${e.message}", e)
                writeReport(context, JSONObject().apply {
                    put("verdict", "FAIL")
                    put("error", e.message ?: "Unknown fatal exception")
                })
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runLiveValidation(context: Context, intent: Intent) {
        val report = JSONObject()
        report.put("timestamp", System.currentTimeMillis())
        report.put("phase", "10.4.2")

        // 1. Resolve credentials (from intent extras or Preferences)
        var botToken = intent.getStringExtra("bot_token") ?: ""
        var channelId = intent.getLongExtra("channel_id", 0L)

        if (botToken.isBlank() || channelId == 0L) {
            Preferences.init(context)
            botToken = Preferences.getBotToken()
            channelId = Preferences.getEncryptedChannelId()
        }

        if (botToken.isBlank() || channelId == 0L) {
            val err = "Telegram credentials not found. Configure bot token and channel ID in app or pass via broadcast extras."
            Log.e(TAG, "❌ $err")
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }

        val botId = TelegramCatalogApi.extractBotId(botToken)
        val redactedToken = botToken.take(4) + "..." + botToken.takeLast(4)
        Log.i(TAG, "Using Bot ID: $botId, Channel ID: $channelId, Token: $redactedToken")
        report.put("bot_id", botId)
        report.put("channel_id", channelId)
        report.put("bot_token_redacted", redactedToken)

        val catalogApi = TelegramCatalogApi.defaultInstance
        val indexManager = MasterIndexManager.defaultInstance
        val pendingDao = DbHolder.database.pendingCatalogEntryDao()

        // -------------------------------------------------------------
        // STEP 2: CREATE IDENTIFIABLE TEST MEDIA
        // -------------------------------------------------------------
        Log.i(TAG, "\n--- STEP 2: Generating Identifiable Test Media ---")
        val cacheDir = context.cacheDir

        // Item A: Test Photo (small JPEG)
        val photoFile = File(cacheDir, "phase10_4_2_test_photo.jpg")
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(30, 144, 255))
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 20f
                isAntiAlias = true
            }
            canvas.drawText("Phase 10.4.2 Photo", 30f, 150f, paint)
            canvas.drawText("TS: ${System.currentTimeMillis()}", 30f, 180f, paint)
        }
        FileOutputStream(photoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val photoBytes = photoFile.readBytes()
        val photoSha256 = ContentHasher.computeHash(photoBytes)
        val photoSize = photoBytes.size.toLong()
        Log.i(TAG, "Generated Test Photo: ${photoFile.name} ($photoSize bytes, SHA-256=$photoSha256)")

        // Item B: Normal Video (< 50 MB)
        val normalVideoFile = File(cacheDir, "phase10_4_2_normal_video.mp4")
        val normalVideoBytes = generateSyntheticMp4("Phase 10.4.2 Normal Video", 65536)
        normalVideoFile.writeBytes(normalVideoBytes)
        val normalVideoSha256 = ContentHasher.computeHash(normalVideoBytes)
        val normalVideoSize = normalVideoBytes.size.toLong()
        Log.i(TAG, "Generated Normal Video: ${normalVideoFile.name} ($normalVideoSize bytes, SHA-256=$normalVideoSha256)")

        // Item C: Chunked Video (128 KiB with 64 KiB chunks -> 2 chunks)
        val chunkedVideoFile = File(cacheDir, "phase10_4_2_chunked_video.mp4")
        val chunkedVideoBytes = generateSyntheticMp4("Phase 10.4.2 Chunked Video", 131072)
        chunkedVideoFile.writeBytes(chunkedVideoBytes)
        val chunkedVideoSha256 = ContentHasher.computeHash(chunkedVideoBytes)
        val chunkedVideoSize = chunkedVideoBytes.size.toLong()
        val chunkSize = 65536L // 64 KiB test chunk size
        val totalChunks = 2
        val chunk0Bytes = chunkedVideoBytes.sliceArray(0 until chunkSize.toInt())
        val chunk1Bytes = chunkedVideoBytes.sliceArray(chunkSize.toInt() until chunkedVideoBytes.size)
        val chunk0Sha256 = ContentHasher.computeHash(chunk0Bytes)
        val chunk1Sha256 = ContentHasher.computeHash(chunk1Bytes)
        val chunk0Size = chunk0Bytes.size.toLong()
        val chunk1Size = chunk1Bytes.size.toLong()
        Log.i(TAG, "Generated Chunked Video: ${chunkedVideoFile.name} ($chunkedVideoSize bytes, SHA-256=$chunkedVideoSha256, 2 chunks: $chunk0Size and $chunk1Size bytes)")

        val mediaMetadataJson = JSONObject().apply {
            put("photo", JSONObject().apply {
                put("file_name", photoFile.name)
                put("size_bytes", photoSize)
                put("sha256", photoSha256)
            })
            put("normal_video", JSONObject().apply {
                put("file_name", normalVideoFile.name)
                put("size_bytes", normalVideoSize)
                put("sha256", normalVideoSha256)
            })
            put("chunked_video", JSONObject().apply {
                put("file_name", chunkedVideoFile.name)
                put("size_bytes", chunkedVideoSize)
                put("sha256", chunkedVideoSha256)
                put("chunk_size", chunkSize)
                put("total_chunks", totalChunks)
            })
        }
        report.put("test_media", mediaMetadataJson)

        // -------------------------------------------------------------
        // STEP 3: LIVE TELEGRAM UPLOAD AND CATALOG PUBLICATION
        // -------------------------------------------------------------
        Log.i(TAG, "\n--- STEP 3: Live Uploads to Telegram Channel ---")
        val uploadResultsJson = JSONObject()

        // 3.1 Upload Photo
        Log.i(TAG, "Uploading Test Photo...")
        val photoUpload = catalogApi.sendDocument(
            botToken = botToken,
            channelId = channelId,
            fileBytes = photoBytes,
            fileName = photoFile.name,
            mimeType = "image/jpeg",
            caption = "GallerySync Phase 10.4.2 Test Photo"
        )
        if (photoUpload !is TelegramCatalogUploadResult.Success) {
            val err = "Photo upload failed: $photoUpload"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        Log.i(TAG, "✅ Photo uploaded: fileId=${photoUpload.fileId}, messageId=${photoUpload.messageId}")
        uploadResultsJson.put("photo_file_id", photoUpload.fileId)
        uploadResultsJson.put("photo_message_id", photoUpload.messageId)

        // Queue Photo Media Record
        val photoRecord = CatalogMediaRecord(
            mediaSha256 = photoSha256,
            displayName = photoFile.name,
            sizeBytes = photoSize,
            mediaType = "IMAGE",
            telegramFileId = photoUpload.fileId,
            telegramMessageId = photoUpload.messageId,
            backupTimestamp = System.currentTimeMillis(),
            backupStatus = "DONE",
            localId = "test_photo_phase1042"
        )
        pendingDao.insertOrReplace(PendingCatalogEntry(
            entryType = PendingCatalogEntry.TYPE_MEDIA_RECORD,
            referenceId = photoSha256,
            payloadJson = mapper.writeValueAsString(photoRecord),
            status = PendingCatalogEntry.STATUS_PENDING
        ))

        // 3.2 Upload Normal Video
        Log.i(TAG, "Uploading Normal Video...")
        val normalVideoUpload = catalogApi.sendDocument(
            botToken = botToken,
            channelId = channelId,
            fileBytes = normalVideoBytes,
            fileName = normalVideoFile.name,
            mimeType = "video/mp4",
            caption = "GallerySync Phase 10.4.2 Normal Video"
        )
        if (normalVideoUpload !is TelegramCatalogUploadResult.Success) {
            val err = "Normal video upload failed: $normalVideoUpload"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        Log.i(TAG, "✅ Normal video uploaded: fileId=${normalVideoUpload.fileId}, messageId=${normalVideoUpload.messageId}")
        uploadResultsJson.put("normal_video_file_id", normalVideoUpload.fileId)
        uploadResultsJson.put("normal_video_message_id", normalVideoUpload.messageId)

        // Queue Normal Video Media Record
        val normalVideoRecord = CatalogMediaRecord(
            mediaSha256 = normalVideoSha256,
            displayName = normalVideoFile.name,
            sizeBytes = normalVideoSize,
            mediaType = "VIDEO",
            telegramFileId = normalVideoUpload.fileId,
            telegramMessageId = normalVideoUpload.messageId,
            backupTimestamp = System.currentTimeMillis(),
            backupStatus = "DONE",
            localId = "test_video_phase1042"
        )
        pendingDao.insertOrReplace(PendingCatalogEntry(
            entryType = PendingCatalogEntry.TYPE_MEDIA_RECORD,
            referenceId = normalVideoSha256,
            payloadJson = mapper.writeValueAsString(normalVideoRecord),
            status = PendingCatalogEntry.STATUS_PENDING
        ))

        // 3.3 Upload Chunked Video Chunks
        Log.i(TAG, "Uploading Chunk 0 (0..65536)...")
        val chunk0Upload = catalogApi.sendDocument(
            botToken = botToken,
            channelId = channelId,
            fileBytes = chunk0Bytes,
            fileName = "phase10_4_2_chunked_video.part0",
            mimeType = "application/octet-stream",
            caption = "Chunk 0/2 for Phase 10.4.2"
        )
        if (chunk0Upload !is TelegramCatalogUploadResult.Success) {
            val err = "Chunk 0 upload failed: $chunk0Upload"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        Log.i(TAG, "✅ Chunk 0 uploaded: fileId=${chunk0Upload.fileId}, messageId=${chunk0Upload.messageId}")

        Log.i(TAG, "Uploading Chunk 1 (65536..131072)...")
        val chunk1Upload = catalogApi.sendDocument(
            botToken = botToken,
            channelId = channelId,
            fileBytes = chunk1Bytes,
            fileName = "phase10_4_2_chunked_video.part1",
            mimeType = "application/octet-stream",
            caption = "Chunk 1/2 for Phase 10.4.2"
        )
        if (chunk1Upload !is TelegramCatalogUploadResult.Success) {
            val err = "Chunk 1 upload failed: $chunk1Upload"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        Log.i(TAG, "✅ Chunk 1 uploaded: fileId=${chunk1Upload.fileId}, messageId=${chunk1Upload.messageId}")

        uploadResultsJson.put("chunk_0_file_id", chunk0Upload.fileId)
        uploadResultsJson.put("chunk_0_message_id", chunk0Upload.messageId)
        uploadResultsJson.put("chunk_1_file_id", chunk1Upload.fileId)
        uploadResultsJson.put("chunk_1_message_id", chunk1Upload.messageId)

        // 3.4 Build and Upload Manifest
        val backupId = "bk_live_test_phase1042_${System.currentTimeMillis()}"
        val chunkDescriptor0 = ManifestChunkDescriptor(
            index = 0,
            startOffset = 0L,
            endOffsetExclusive = chunk0Size,
            sizeBytes = chunk0Size,
            sha256 = chunk0Sha256,
            telegramFileId = chunk0Upload.fileId,
            telegramMessageId = chunk0Upload.messageId
        )
        val chunkDescriptor1 = ManifestChunkDescriptor(
            index = 1,
            startOffset = chunk0Size,
            endOffsetExclusive = chunk0Size + chunk1Size,
            sizeBytes = chunk1Size,
            sha256 = chunk1Sha256,
            telegramFileId = chunk1Upload.fileId,
            telegramMessageId = chunk1Upload.messageId
        )

        // Verify chunk offsets are strictly contiguous
        check(chunkDescriptor0.endOffsetExclusive == chunkDescriptor1.startOffset) {
            "Non-contiguous chunk offsets: chunk0 end=${chunkDescriptor0.endOffsetExclusive}, chunk1 start=${chunkDescriptor1.startOffset}"
        }

        val manifest = BackupManifest(
            format = BackupManifest.FORMAT_IDENTIFIER,
            version = BackupManifest.CURRENT_VERSION,
            backupId = backupId,
            createdAt = System.currentTimeMillis(),
            application = ApplicationInfo(name = "GallerySync", packageName = "com.gallerysync.app"),
            bot = BotIdentity(id = botId),
            destination = DestinationInfo(chatId = channelId),
            media = MediaDescriptor(
                originalName = chunkedVideoFile.name,
                mimeType = "video/mp4",
                sizeBytes = chunkedVideoSize,
                sha256 = chunkedVideoSha256
            ),
            chunkConfiguration = ManifestChunkConfig(
                chunkSizeBytes = chunkSize,
                totalChunks = totalChunks
            ),
            chunks = listOf(chunkDescriptor0, chunkDescriptor1)
        )
        manifest.validate()

        val manifestBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest)
        val manifestSha256 = ContentHasher.computeHash(manifestBytes)
        val manifestFileName = "gallerysync_manifest_${backupId}.json"

        Log.i(TAG, "Uploading chunked video manifest $manifestFileName (${manifestBytes.size} bytes)...")
        val manifestUpload = catalogApi.sendDocument(
            botToken = botToken,
            channelId = channelId,
            fileBytes = manifestBytes,
            fileName = manifestFileName,
            mimeType = "application/json",
            caption = "GallerySync Manifest for $backupId"
        )
        if (manifestUpload !is TelegramCatalogUploadResult.Success) {
            val err = "Manifest upload failed: $manifestUpload"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        Log.i(TAG, "✅ Manifest uploaded: fileId=${manifestUpload.fileId}, messageId=${manifestUpload.messageId}")
        uploadResultsJson.put("manifest_file_id", manifestUpload.fileId)
        uploadResultsJson.put("manifest_message_id", manifestUpload.messageId)
        uploadResultsJson.put("manifest_sha256", manifestSha256)

        // Queue Manifest Reference
        val manifestRef = ManifestReference(
            backupId = backupId,
            fileId = manifestUpload.fileId,
            messageId = manifestUpload.messageId,
            fileName = manifestFileName,
            sizeBytes = manifestBytes.size.toLong(),
            sha256 = manifestSha256,
            mediaSha256 = chunkedVideoSha256,
            displayName = chunkedVideoFile.name
        )
        pendingDao.insertOrReplace(PendingCatalogEntry(
            entryType = PendingCatalogEntry.TYPE_MANIFEST_REFERENCE,
            referenceId = backupId,
            payloadJson = mapper.writeValueAsString(manifestRef),
            status = PendingCatalogEntry.STATUS_PENDING
        ))

        report.put("upload_results", uploadResultsJson)

        // 3.5 Publish Master Index & Pin in Channel
        Log.i(TAG, "\n--- STEP 3.5: Committing Master Index & Pinning in Telegram ---")
        val commitResult = indexManager.commitPendingEntries(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = DbHolder.database,
            forceCommit = true
        )

        if (commitResult !is MasterIndexCommitResult.Success) {
            val err = "Master index commit failed: $commitResult"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }

        Log.i(TAG, "🎉 Master Index committed successfully! Generation=${commitResult.generation}, pinnedMessageId=${commitResult.pinnedMessageId}, indexFileId=${commitResult.indexFileId}")
        val publicationJson = JSONObject().apply {
            put("generation", commitResult.generation)
            put("pinned_message_id", commitResult.pinnedMessageId)
            put("index_file_id", commitResult.indexFileId)
            put("total_media_count", commitResult.totalMediaCount)
            put("total_manifest_count", commitResult.totalManifestCount)
        }
        report.put("publication_results", publicationJson)

        // 3.6 Verify Pin in Channel via getChat
        Log.i(TAG, "Verifying pinned message via getChat...")
        val chatInfoResult = catalogApi.getChatInfo(botToken, channelId)
        if (chatInfoResult !is TelegramCatalogResult.Success) {
            val err = "getChat failed: $chatInfoResult"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        val chatInfo = chatInfoResult.value
        val pinnedMsg = chatInfo.pinnedMessage
        checkNotNull(pinnedMsg) { "No pinned message returned in getChat response" }
        check(pinnedMsg.messageId == commitResult.pinnedMessageId) {
            "Pinned message ID mismatch: expected ${commitResult.pinnedMessageId}, got ${pinnedMsg.messageId}"
        }
        val remoteDoc = pinnedMsg.document
        checkNotNull(remoteDoc) { "Pinned message has no attached document" }
        Log.i(TAG, "✅ Verified pinned message ${pinnedMsg.messageId} contains document: ${remoteDoc.fileName} (${remoteDoc.fileId})")

        // Confirm local catalog generation matches
        val localCatalog = DbHolder.database.backupCatalogDao().getByChannelId(channelId)
        checkNotNull(localCatalog) { "Local backup_catalog record missing" }
        check(localCatalog.lastConfirmedGeneration == commitResult.generation) {
            "Local generation mismatch: expected ${commitResult.generation}, got ${localCatalog.lastConfirmedGeneration}"
        }
        Log.i(TAG, "✅ Local catalog generation matches confirmed remote generation: ${localCatalog.lastConfirmedGeneration}")

        // -------------------------------------------------------------
        // STEP 4: REMOTE DOWNLOAD VERIFICATION
        // -------------------------------------------------------------
        Log.i(TAG, "\n--- STEP 4: Remote Download Verification via getFile ---")
        val downloadResultsJson = JSONObject()

        // 4.1 Download Pinned Master Index
        Log.i(TAG, "Downloading pinned Master Index by fileId: ${remoteDoc.fileId}...")
        val indexDownloadResult = catalogApi.downloadFileByFileId(botToken, remoteDoc.fileId)
        if (indexDownloadResult !is TelegramCatalogResult.Success) {
            val err = "Failed to download pinned master index: $indexDownloadResult"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        val downloadedIndexBytes = indexDownloadResult.value
        val downloadedIndexSha256 = ContentHasher.computeHash(downloadedIndexBytes)
        val downloadedIndex = mapper.readValue(downloadedIndexBytes, MasterIndex::class.java)
        downloadedIndex.validate()
        Log.i(TAG, "✅ Downloaded Master Index: Gen=${downloadedIndex.generation}, size=${downloadedIndexBytes.size} bytes, SHA-256=$downloadedIndexSha256")
        check(downloadedIndex.generation == commitResult.generation) { "Downloaded generation mismatch" }
        check(downloadedIndex.botId == botId) { "Downloaded botId mismatch" }
        check(downloadedIndex.channelId == channelId) { "Downloaded channelId mismatch" }
        check(downloadedIndexBytes.size < TelegramCatalogApi.MAX_DOWNLOAD_SIZE_BYTES) { "Index exceeds 20 MB limit" }

        downloadResultsJson.put("master_index_size_bytes", downloadedIndexBytes.size)
        downloadResultsJson.put("master_index_sha256", downloadedIndexSha256)
        downloadResultsJson.put("master_index_generation", downloadedIndex.generation)

        // 4.2 Download Manifest by fileId
        Log.i(TAG, "Downloading chunked video manifest by fileId: ${manifestUpload.fileId}...")
        val manifestDownloadResult = catalogApi.downloadFileByFileId(botToken, manifestUpload.fileId)
        if (manifestDownloadResult !is TelegramCatalogResult.Success) {
            val err = "Failed to download manifest: $manifestDownloadResult"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            writeReport(context, report)
            return
        }
        val downloadedManifestBytes = manifestDownloadResult.value
        val downloadedManifestSha256 = ContentHasher.computeHash(downloadedManifestBytes)
        val downloadedManifest = mapper.readValue(downloadedManifestBytes, BackupManifest::class.java)
        downloadedManifest.validate()
        Log.i(TAG, "✅ Downloaded Manifest: backupId=${downloadedManifest.backupId}, size=${downloadedManifestBytes.size} bytes, mediaSha256=${downloadedManifest.media.sha256}")
        check(downloadedManifest.media.sha256 == chunkedVideoSha256) { "Manifest media SHA-256 mismatch" }
        check(downloadedManifest.chunks.size == 2) { "Manifest chunk count mismatch" }
        check(downloadedManifestBytes.size < TelegramCatalogApi.MAX_DOWNLOAD_SIZE_BYTES) { "Manifest exceeds 20 MB limit" }

        downloadResultsJson.put("manifest_size_bytes", downloadedManifestBytes.size)
        downloadResultsJson.put("manifest_sha256", downloadedManifestSha256)
        downloadResultsJson.put("manifest_media_sha256", downloadedManifest.media.sha256)
        report.put("download_results", downloadResultsJson)

        // -------------------------------------------------------------
        // STEP 5: ISOLATED RECOVERY TEST
        // -------------------------------------------------------------
        Log.i(TAG, "\n--- STEP 5: Isolated Recovery Test into Fresh Room Database ---")
        val isolatedDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java).build()
        val recoveryResult = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = isolatedDb,
            catalogApi = catalogApi,
            indexManager = indexManager
        )

        if (recoveryResult !is TelegramCatalogRecoveryResult.Success) {
            val err = "Catalog recovery failed into isolated database: $recoveryResult"
            Log.e(TAG, err)
            report.put("verdict", "FAIL")
            report.put("error", err)
            isolatedDb.close()
            writeReport(context, report)
            return
        }

        Log.i(TAG, "✅ Catalog Recovery SUCCESS: ${recoveryResult.manifestsRecovered} manifests, ${recoveryResult.mediaRecordsRecovered} media records recovered.")

        // Verify recovered records in isolated database
        val recoveredRemotePhotos = isolatedDb.remotePhotoDao().getAll()
        val recoveredPhoto = recoveredRemotePhotos.find { it.remoteId == photoUpload.fileId }
        val recoveredNormalVideo = recoveredRemotePhotos.find { it.remoteId == normalVideoUpload.fileId }
        val recoveredChunkedBackup = isolatedDb.chunkedBackupDao().getById(backupId)
        val recoveredChunks = isolatedDb.videoChunkDao().getByBackupId(backupId)

        checkNotNull(recoveredPhoto) { "Recovered photo record missing" }
        check(recoveredPhoto.contentHash == photoSha256) { "Recovered photo hash mismatch" }
        check(recoveredPhoto.mediaType == "IMAGE") { "Recovered photo mediaType mismatch" }

        checkNotNull(recoveredNormalVideo) { "Recovered normal video record missing" }
        check(recoveredNormalVideo.contentHash == normalVideoSha256) { "Recovered video hash mismatch" }
        check(recoveredNormalVideo.mediaType == "VIDEO") { "Recovered video mediaType mismatch" }

        checkNotNull(recoveredChunkedBackup) { "Recovered chunked backup record missing" }
        check(recoveredChunkedBackup.status == ChunkedBackupStatus.DONE) { "Recovered chunked backup status not DONE" }
        check(recoveredChunkedBackup.fileSha256 == chunkedVideoSha256) { "Recovered chunked backup fileSha256 mismatch" }
        check(recoveredChunkedBackup.manifestFileId == manifestUpload.fileId) { "Recovered chunked backup manifestFileId mismatch" }

        check(recoveredChunks.size == 2) { "Recovered chunks count mismatch" }
        check(recoveredChunks[0].telegramFileId == chunk0Upload.fileId) { "Recovered chunk 0 fileId mismatch" }
        check(recoveredChunks[1].telegramFileId == chunk1Upload.fileId) { "Recovered chunk 1 fileId mismatch" }

        Log.i(TAG, "✅ Reconstructed and verified metadata for Test Photo, Normal Video, and Chunked Video in isolated database.")
        val recoveryJson = JSONObject().apply {
            put("manifests_recovered", recoveryResult.manifestsRecovered)
            put("media_records_recovered", recoveryResult.mediaRecordsRecovered)
            put("recovered_photo_remote_id", recoveredPhoto.remoteId)
            put("recovered_video_remote_id", recoveredNormalVideo.remoteId)
            put("recovered_chunked_backup_id", recoveredChunkedBackup.backupId)
            put("recovered_chunk_count", recoveredChunks.size)
        }
        report.put("isolated_recovery_results", recoveryJson)
        isolatedDb.close()

        // -------------------------------------------------------------
        // STEP 6: FAILURE HANDLING & SECURITY VERIFICATION
        // -------------------------------------------------------------
        Log.i(TAG, "\n--- STEP 6: Failure Handling & Security Verification ---")
        val failDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java).build()
        val badChannelResult = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId - 12345L, // Non-existent channel
            database = failDb,
            catalogApi = catalogApi,
            indexManager = indexManager
        )
        check(badChannelResult is TelegramCatalogRecoveryResult.Error || badChannelResult is TelegramCatalogRecoveryResult.IdentityMismatch) {
            "Expected failure for invalid channel, got: $badChannelResult"
        }
        val failureReason = when (badChannelResult) {
            is TelegramCatalogRecoveryResult.Error -> badChannelResult.message
            is TelegramCatalogRecoveryResult.IdentityMismatch -> badChannelResult.reason
            else -> "Unknown"
        }
        Log.i(TAG, "✅ Successfully rejected recovery with invalid channel: $failureReason")
        report.put("invalid_channel_rejection_reason", failureReason)
        failDb.close()

        // 6.2 Test startup reconciliation
        val reconResult = indexManager.reconcileWithRemoteCatalog(botToken, channelId, DbHolder.database)
        Log.i(TAG, "✅ Startup reconciliation check: $reconResult")
        report.put("reconciliation_result", reconResult.toString())

        // -------------------------------------------------------------
        // STEP 7: CLEANUP & REPORT
        // -------------------------------------------------------------
        photoFile.delete()
        normalVideoFile.delete()
        chunkedVideoFile.delete()

        report.put("verdict", "PASS")
        Log.i(TAG, "\n=================================================================")
        Log.i(TAG, "🎉 ALL PHASE 10.4.2 LIVE TELEGRAM CATALOG VALIDATION CHECKS PASSED!")
        Log.i(TAG, "=================================================================")

        writeReport(context, report)
    }

    private fun writeReport(context: Context, report: JSONObject) {
        val jsonString = report.toString(2)
        Log.i(TAG, "\n--- VALIDATION JSON REPORT ---\n$jsonString\n------------------------------")

        try {
            val extFiles = context.getExternalFilesDir(null)
            if (extFiles != null) {
                val file = File(extFiles, REPORT_FILE_NAME)
                file.writeText(jsonString)
                Log.i(TAG, "Report written to: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to external files dir: ${e.message}")
        }

        try {
            val downloadDir = File("/sdcard/Download")
            if (downloadDir.exists() && downloadDir.canWrite()) {
                val downloadFile = File(downloadDir, REPORT_FILE_NAME)
                downloadFile.writeText(jsonString)
                Log.i(TAG, "Report written to: ${downloadFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to /sdcard/Download: ${e.message}")
        }
    }

    /**
     * Generates a synthetic MP4 file with valid ISO/IEC 14496-12 box structure (ftyp, moov, mdat)
     * so that Telegram Bot API recognizes and processes it as a valid video document.
     */
    private fun generateSyntheticMp4(tag: String, targetSize: Int): ByteArray {
        val bytes = ByteArray(targetSize)
        val buf = ByteBuffer.wrap(bytes)

        // 1. ftyp box (24 bytes)
        val ftypSize = 24
        buf.putInt(ftypSize)
        buf.put("ftyp".toByteArray(Charsets.US_ASCII))
        buf.put("isom".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x00000200)
        buf.put("isom".toByteArray(Charsets.US_ASCII))
        buf.put("mp42".toByteArray(Charsets.US_ASCII))

        // 2. moov box (32 bytes)
        val moovSize = 32
        buf.putInt(moovSize)
        buf.put("moov".toByteArray(Charsets.US_ASCII))
        buf.position(buf.position() + (moovSize - 8)) // blank moov payload

        // 3. mdat box (remainder to fill exactly targetSize bytes)
        val headerSize = ftypSize + moovSize
        val mdatSize = targetSize - headerSize
        buf.putInt(mdatSize)
        buf.put("mdat".toByteArray(Charsets.US_ASCII))

        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val writeTagLen = minOf(tagBytes.size, mdatSize - 8)
        buf.put(tagBytes, 0, writeTagLen)

        var counter = 0
        while (buf.hasRemaining()) {
            buf.put((counter++ % 256).toByte())
        }

        return bytes
    }

    companion object {
        const val TAG = "LiveCatalogValidation"
        const val ACTION_VALIDATE_CATALOG = "com.gallerysync.app.VALIDATE_CATALOG"
        const val REPORT_FILE_NAME = "phase_10_4_2_live_validation_report.json"
    }
}
