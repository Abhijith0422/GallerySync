package com.gallerysync.app.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.utils.ImageMetadataExtractor
import com.gallerysync.app.utils.MetadataConfig
import com.gallerysync.app.utils.NotificationHelper
import com.gallerysync.app.utils.escapeHtml
import com.gallerysync.app.utils.getExtFromMimeType
import com.gallerysync.app.utils.getFileName
import com.gallerysync.app.utils.getMimeTypeFromUri
import com.github.kotlintelegrambot.entities.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Centralized upload dispatcher and error classifier for Telegram media backup.
 * Handles images, MP4 videos, and non-MP4 videos with 50 MB size validation,
 * bounded streaming, and safe temporary file cleanup.
 */
object TelegramUploadDispatcher {

    private const val TAG = "UploadDispatcher"
    const val MAX_TELEGRAM_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB hosted bot limit for documents/images
    const val DIRECT_UPLOAD_MAX_BYTES = com.gallerysync.app.data.chunking.ChunkConfiguration.DIRECT_UPLOAD_MAX_BYTES // 16 MiB (16,777,216 bytes) single authoritative threshold for direct videos

    enum class UploadDispatchType {
        VIDEO_MP4,
        DOCUMENT_VIDEO,
        DOCUMENT_IMAGE
    }

    /**
     * Determines whether to upload via sendVideo or sendDocument based on media type and format.
     */
    fun determineDispatchType(mediaType: String, photoType: String, mimeType: String?): UploadDispatchType {
        val isVideo = mediaType == "VIDEO" || mimeType?.startsWith("video/") == true
        if (isVideo) {
            val ext = photoType.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return if (ext == "mp4" || ext == "m4v" || mime == "video/mp4") {
                UploadDispatchType.VIDEO_MP4
            } else {
                UploadDispatchType.DOCUMENT_VIDEO
            }
        }
        return UploadDispatchType.DOCUMENT_IMAGE
    }

    /**
     * Classifies upload errors to distinguish transient network/server failures from permanent blocks.
     */
    sealed class UploadError(val errorMessage: String, val isRetryable: Boolean) : Exception(errorMessage) {
        class NetworkUnavailable(msg: String = "Network is unavailable or connection timed out", cause: Throwable? = null) :
            UploadError(msg, isRetryable = true)

        class RateLimited(val retryAfterSeconds: Int, msg: String = "Telegram rate limit (retry after ${retryAfterSeconds}s)") :
            UploadError(msg, isRetryable = true)

        class ServerError(val code: Int, msg: String = "Telegram server error $code") :
            UploadError(msg, isRetryable = true)

        class InvalidCredentials(val code: Int, msg: String = "Invalid Telegram bot token or chat permissions ($code)") :
            UploadError(msg, isRetryable = false)

        class FileExceedsLimit(val actualSize: Long, msg: String = "File size (${actualSize / (1024 * 1024)} MB) exceeds Telegram's 50 MB limit") :
            UploadError(msg, isRetryable = false)

        class SourceMissing(msg: String = "Source file missing or inaccessible") :
            UploadError(msg, isRetryable = false)

        class UnsupportedFormat(msg: String = "Unsupported media format") :
            UploadError(msg, isRetryable = false)

        class ApiError(val code: Int, val description: String, isRetryable: Boolean = false) :
            UploadError("Telegram API error $code: $description", isRetryable)
    }

    /**
     * Sanitizes sensitive bot tokens and API credentials from error messages before logging or displaying.
     */
    fun sanitizeErrorMessage(message: String?): String {
        if (message.isNullOrBlank()) return "Unknown upload failure"
        return message
            .replace(Regex("/bot[^/\\s]+"), "/bot[REDACTED]")
            .replace(Regex("\\b\\d{8,11}:[A-Za-z0-9_-]{20,50}\\b"), "[REDACTED_BOT_TOKEN]")
    }

    /**
     * Maps HTTP status codes and exceptions to structured [UploadError].
     */
    fun classifyError(e: Throwable?, responseCode: Int? = null, errorBody: String? = null): UploadError {
        if (e is UploadError) return e
        val safeMessage = sanitizeErrorMessage(e?.message)
        if (e is FileNotFoundException) return UploadError.SourceMissing(safeMessage)
        if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException) {
            return UploadError.NetworkUnavailable(safeMessage, e)
        }

        if (responseCode != null) {
            val safeErrorBody = sanitizeErrorMessage(errorBody)
            when {
                responseCode == 429 -> {
                    val retryAfter = parseRetryAfter(errorBody) ?: 30
                    return UploadError.RateLimited(retryAfter)
                }
                responseCode in 500..599 -> return UploadError.ServerError(responseCode)
                responseCode == 401 || responseCode == 403 -> return UploadError.InvalidCredentials(responseCode, safeErrorBody)
                responseCode == 400 && errorBody?.contains("file is too big", ignoreCase = true) == true -> {
                    return UploadError.FileExceedsLimit(0L, safeErrorBody)
                }
                else -> return UploadError.ApiError(responseCode, safeErrorBody)
            }
        }

        return UploadError.ApiError(0, safeMessage)
    }

    private fun parseRetryAfter(errorBody: String?): Int? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = JSONObject(errorBody)
            if (json.has("parameters")) {
                val params = json.getJSONObject("parameters")
                if (params.has("retry_after")) params.getInt("retry_after") else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks source media size from ContentResolver without reading bytes into RAM.
     */
    fun getSourceFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Centralized upload dispatcher. Safely handles file-size validation,
     * streams media to temporary storage, dispatches to Telegram API,
     * updates database records, and guarantees temp file cleanup.
     */
    suspend fun uploadMedia(
        context: Context,
        photo: Photo,
        botApi: BotApi,
        channelId: Long,
        uploadType: String?,
        messageThreadId: Long? = null,
        topicName: String? = null,
        previewId: String? = null,
        previewMessageId: Long? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        val uri = photo.pathUri.toUri()
        val contentResolver = context.contentResolver

        // 1. Initial source verification
        val rawSourceSize = getSourceFileSize(context, uri)
        val mimeType = getMimeTypeFromUri(contentResolver, uri) ?: "image/jpeg"
        val ext = getExtFromMimeType(mimeType) ?: photo.photoType
        val originalFileName = getFileName(contentResolver, uri) ?: "${photo.localId}.$ext"
        val dispatchType = determineDispatchType(photo.mediaType, ext, mimeType)

        // 2. Pre-check: if media exceeds authoritative direct upload limit, do not create temp file or attempt upload
        val maxAllowedPreCheck = if (dispatchType != UploadDispatchType.DOCUMENT_IMAGE) DIRECT_UPLOAD_MAX_BYTES else MAX_TELEGRAM_FILE_SIZE_BYTES
        if (rawSourceSize > maxAllowedPreCheck) {
            Log.w(TAG, "Media ${photo.localId} ($rawSourceSize bytes) exceeds direct upload limit ($maxAllowedPreCheck bytes), skipping direct upload")
            throw UploadError.FileExceedsLimit(rawSourceSize)
        }

        var tempFile: File? = null
        try {
            // 3. Stream bounded chunks to temporary file (never full video into memory)
            tempFile = File.createTempFile("up_${photo.localId}_", ".$ext")
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            } ?: throw UploadError.SourceMissing("Cannot open input stream for $uri")

            if (copied <= 0L && tempFile.length() <= 0L) {
                throw UploadError.SourceMissing("Source file is empty (0 bytes): $uri")
            }

            var actualFileSize = tempFile.length()

            // 4. File-size validation
            val maxAllowedActualSize = if (dispatchType != UploadDispatchType.DOCUMENT_IMAGE) DIRECT_UPLOAD_MAX_BYTES else MAX_TELEGRAM_FILE_SIZE_BYTES
            if (actualFileSize > maxAllowedActualSize) {
                if (dispatchType != UploadDispatchType.DOCUMENT_IMAGE) {
                    // Videos must NEVER be silently compressed
                    throw UploadError.FileExceedsLimit(actualFileSize)
                } else {
                    // Images: compress according to existing rules
                    Log.i(TAG, "Image ${photo.localId} ($actualFileSize bytes) exceeds 50 MB; applying compression")
                    compressImageFile(tempFile, mimeType)
                    actualFileSize = tempFile.length()
                    if (actualFileSize > MAX_TELEGRAM_FILE_SIZE_BYTES) {
                        throw UploadError.FileExceedsLimit(actualFileSize)
                    }
                }
            }

            // 5. Build caption with metadata and tags
            val caption = buildCaption(context, photo, uri)

            // 5b. Generate & upload 20-40 KB video frame thumbnail preview if not already provided
            var effectivePreviewId = previewId
            var effectivePreviewMessageId = previewMessageId
            if (dispatchType != UploadDispatchType.DOCUMENT_IMAGE && effectivePreviewId == null) {
                try {
                    val vPreview = com.gallerysync.app.utils.generateVideoPreview(context, uri)
                    if (vPreview != null) {
                        val (uploadedPId, uploadedPMsgId) = com.gallerysync.app.utils.uploadPreviewFile(
                            botApi = botApi,
                            channelId = channelId,
                            file = vPreview,
                            contentHash = photo.contentHash,
                            messageThreadId = messageThreadId,
                            topicName = topicName
                        )
                        effectivePreviewId = uploadedPId
                        effectivePreviewMessageId = uploadedPMsgId
                        if (uploadedPId != null) {
                            DbHolder.database.photoDao().updatePreviewRemoteId(photo.localId, uploadedPId)
                        }
                        vPreview.delete()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed generating video preview for direct upload ${photo.localId}", e)
                }
            }

            // 6. Execute dispatch to Telegram API
            val (message, uploadError, uploadedToTopic) = executeDispatch(
                botApi = botApi,
                channelId = channelId,
                file = tempFile,
                dispatchType = dispatchType,
                photo = photo,
                caption = caption,
                previewMessageId = effectivePreviewMessageId,
                messageThreadId = messageThreadId,
                topicName = topicName
            )

            if (uploadError != null || message == null) {
                throw uploadError ?: UploadError.ApiError(0, "Upload returned null message")
            }

            // 7. Extract remote IDs and save to Room
            val remoteId = extractRemoteId(message)
                ?: throw UploadError.ApiError(0, "No fileId found in Telegram response")

            val finalMediaType = if (dispatchType == UploadDispatchType.DOCUMENT_IMAGE) "IMAGE" else "VIDEO"
            val deviceId = Preferences.getOrCreateDeviceId()

            // Extract thumbnail ID from video or document response, or keep existing previewId if provided
            val extractedThumbId = extractPreviewRemoteId(message)
            val effectivePreviewRemoteId = extractedThumbId ?: effectivePreviewId

            saveRemotePhoto(
                photo = photo,
                remoteId = remoteId,
                message = message,
                fileSize = actualFileSize,
                fileName = originalFileName,
                extension = ext,
                mediaType = finalMediaType,
                deviceId = deviceId,
                uploadType = uploadType,
                previewRemoteId = effectivePreviewRemoteId,
                uploadedToTopic = uploadedToTopic,
                topicId = messageThreadId,
                topicName = topicName
            )

            // 8. Notify user for video upload success
            if (finalMediaType == "VIDEO") {
                NotificationHelper.showVideoUploadSuccessNotification(context, originalFileName)
            }

            UploadResult.Success(
                fileId = remoteId,
                messageId = message.messageId.toLong(),
                mediaType = finalMediaType,
                fileSize = actualFileSize
            )
        } finally {
            // Guaranteed cleanup: temporary files are ALWAYS deleted
            tempFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        }
    }

    /**
     * Executes the appropriate Telegram Bot API method and handles forum topic fallback.
     */
    private suspend fun executeDispatch(
        botApi: BotApi,
        channelId: Long,
        file: File,
        dispatchType: UploadDispatchType,
        photo: Photo,
        caption: String?,
        previewMessageId: Long?,
        messageThreadId: Long?,
        topicName: String?
    ): Triple<Message?, UploadError?, Boolean> {
        var message: Message? = null
        var uploadError: UploadError? = null
        var uploadedToTopic = false

        val callUpload: suspend (Long?) -> Pair<Message?, UploadError?> = { threadId ->
            when (dispatchType) {
                UploadDispatchType.VIDEO_MP4 -> {
                    val durationSec = photo.duration?.let { (it / 1000L).toInt() }
                    val (res, err) = botApi.sendVideo(
                        file = file,
                        channelId = channelId,
                        duration = durationSec,
                        width = photo.width,
                        height = photo.height,
                        caption = caption,
                        supportsStreaming = true,
                        replyToMessageId = null,
                        messageThreadId = threadId
                    )
                    parseApiResponse(res, err)
                }
                UploadDispatchType.DOCUMENT_VIDEO -> {
                    // Non-MP4 video sent via sendDocument to preserve format
                    val (res, err) = botApi.sendFile(
                        file = file,
                        channelId = channelId,
                        caption = caption,
                        replyToMessageId = null,
                        messageThreadId = threadId
                    )
                    parseApiResponse(res, err)
                }
                UploadDispatchType.DOCUMENT_IMAGE -> {
                    val (res, err) = botApi.sendFile(
                        file = file,
                        channelId = channelId,
                        caption = caption,
                        replyToMessageId = previewMessageId,
                        messageThreadId = threadId
                    )
                    parseApiResponse(res, err)
                }
            }
        }

        // Attempt 1: Upload with configured thread/topic (or root for private channel)
        val (msg1, err1) = callUpload(messageThreadId)
        message = msg1
        uploadError = err1
        if (err1 == null && msg1 != null) {
            uploadedToTopic = messageThreadId != null
            return Triple(message, null, uploadedToTopic)
        }

        // Fallback: If topic upload failed, retry directly to channel root (messageThreadId = null)
        if (messageThreadId != null && uploadError != null) {
            Log.w(TAG, "Topic upload failed (${uploadError.errorMessage}), retrying to channel root")
            botApi.removeTopicFromCache(topicName)
            val (msg2, err2) = callUpload(null)
            message = msg2
            uploadError = err2
        }

        return Triple(message, uploadError, false)
    }

    private fun parseApiResponse(
        response: retrofit2.Response<com.github.kotlintelegrambot.network.Response<Message>?>?,
        error: Exception?
    ): Pair<Message?, UploadError?> {
        if (error != null) {
            return Pair(null, classifyError(error))
        }
        if (response != null) {
            if (response.isSuccessful) {
                val body = response.body()
                val message = body?.result
                if (message != null) {
                    return Pair(message, null)
                }
            }
            val code = response.code()
            val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
            return Pair(null, classifyError(null, code, errBody))
        }
        return Pair(null, UploadError.ApiError(0, "Both response and error are null"))
    }

    private fun extractRemoteId(message: Message): String? {
        val video = message.video
        val doc = message.document
        val photoSize = message.photo?.maxByOrNull { it.fileSize ?: 0 }
        val sticker = message.sticker
        return video?.fileId ?: doc?.fileId ?: photoSize?.fileId ?: sticker?.fileId
    }

    /**
     * Extracts thumbnail fileId from a video or document message response.
     * Returns null if no thumbnail was provided by Telegram.
     */
    fun extractPreviewRemoteId(message: Message): String? {
        val videoThumb = message.video?.thumb?.fileId
        val docThumb = message.document?.thumb?.fileId
        return videoThumb ?: docThumb
    }

    private suspend fun saveRemotePhoto(
        photo: Photo,
        remoteId: String,
        message: Message,
        fileSize: Long,
        fileName: String,
        extension: String,
        mediaType: String,
        deviceId: String,
        uploadType: String?,
        previewRemoteId: String?,
        uploadedToTopic: Boolean,
        topicId: Long?,
        topicName: String?
    ) {
        val video = message.video
        val photoDao = DbHolder.database.photoDao()
        val remotePhotoDao = DbHolder.database.remotePhotoDao()

        // 1. Update local photo table
        photoDao.updateRemoteIdForLocalId(photo.localId, remoteId)
        photoDao.updateUploadStatus(photo.localId, "DONE", System.currentTimeMillis())

        // 2. Insert into remote_photos
        val remotePhoto = RemotePhoto(
            remoteId = remoteId,
            photoType = extension,
            fileName = fileName,
            fileSize = fileSize,
            uploadedAt = System.currentTimeMillis(),
            thumbnailCached = false,
            localId = photo.localId,
            messageId = message.messageId,
            uploadType = uploadType,
            contentHash = photo.contentHash,
            uploadedByDevice = deviceId,
            previewRemoteId = previewRemoteId,
            mediaType = mediaType,
            width = photo.width ?: video?.width,
            height = photo.height ?: video?.height,
            duration = photo.duration ?: video?.duration?.toLong()?.times(1000L),
            topicId = if (uploadedToTopic) topicId else null,
            topicName = if (uploadedToTopic) topicName else null
        )
        remotePhotoDao.insertAll(remotePhoto)
        Log.i(TAG, "Persisted RemotePhoto: remoteId=$remoteId, mediaType=$mediaType, messageId=${message.messageId}")

        // 3. Queue PendingCatalogEntry for MasterIndex
        try {
            val pendingDao = DbHolder.database.pendingCatalogEntryDao()
            val catalogRecord = CatalogMediaRecord(
                mediaSha256 = photo.contentHash ?: "",
                displayName = fileName,
                sizeBytes = fileSize,
                mediaType = mediaType,
                telegramFileId = remoteId,
                telegramMessageId = message.messageId.toLong(),
                backupTimestamp = System.currentTimeMillis(),
                backupStatus = "DONE",
                localId = photo.localId
            )
            val json = ObjectMapper().writeValueAsString(catalogRecord)
            val entry = PendingCatalogEntry(
                entryType = PendingCatalogEntry.TYPE_MEDIA_RECORD,
                referenceId = if (!photo.contentHash.isNullOrBlank()) photo.contentHash!! else remoteId,
                payloadJson = json,
                createdAt = System.currentTimeMillis(),
                status = PendingCatalogEntry.STATUS_PENDING
            )
            pendingDao.insertOrReplace(entry)
            Log.d(TAG, "Enqueued PendingCatalogEntry for $remoteId (hash=${photo.contentHash})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue PendingCatalogEntry for $remoteId: ${e.message}", e)
        }
    }

    private suspend fun buildCaption(context: Context, photo: Photo, uri: Uri): String {
        val metadataCaption = if (MetadataConfig.shouldIncludeMetadata()) {
            val metadata = ImageMetadataExtractor.extractMetadata(context, uri)
            metadata?.toTelegramCaption()
        } else null

        val deviceId = Preferences.getOrCreateDeviceId()
        val deviceName = Preferences.getDeviceName()

        return buildString {
            if (metadataCaption != null) {
                append(metadataCaption)
                append("\n")
            }
            appendLine("\u2501".repeat(18))
            appendLine("<b>#device:</b> $deviceId (<b>${escapeHtml(deviceName)}</b>)")
            if (photo.contentHash != null) {
                appendLine("<b>#hash:</b> ${photo.contentHash}")
            }
        }
    }

    private fun compressImageFile(file: File, mimeType: String) {
        try {
            val bytes = file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val format = when (mimeType) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            var quality = 95
            var outputBytes: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                stream.use {
                    bitmap.compress(format, quality, it)
                    outputBytes = it.toByteArray()
                }
                quality -= 10
            } while (outputBytes.size > MAX_TELEGRAM_FILE_SIZE_BYTES && quality > 10)

            file.writeBytes(outputBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image file", e)
        }
    }

    sealed class UploadResult {
        data class Success(
            val fileId: String,
            val messageId: Long,
            val mediaType: String,
            val fileSize: Long
        ) : UploadResult()
    }
}
