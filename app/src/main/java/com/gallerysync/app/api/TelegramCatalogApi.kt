package com.gallerysync.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Result hierarchy for Telegram catalog upload operations (manifests, shards, master index).
 */
sealed class TelegramCatalogUploadResult {
    data class Success(
        val fileId: String,
        val messageId: Long,
        val fileSize: Long,
        val chatId: Long
    ) : TelegramCatalogUploadResult()

    data class RateLimited(val retryAfterSeconds: Int, val description: String) : TelegramCatalogUploadResult()
    data class Unauthorized(val code: Int, val description: String) : TelegramCatalogUploadResult()
    data class PermissionDenied(val code: Int, val description: String) : TelegramCatalogUploadResult()
    data class InvalidResponse(val reason: String) : TelegramCatalogUploadResult()
    data class ServerError(val code: Int, val description: String) : TelegramCatalogUploadResult()
    data class NetworkError(val message: String, val cause: Throwable? = null) : TelegramCatalogUploadResult()
}

/**
 * Generic result hierarchy for Telegram catalog control calls (pin, getChat, download, etc.).
 */
sealed class TelegramCatalogResult<out T> {
    data class Success<out T>(val value: T) : TelegramCatalogResult<T>()
    data class RateLimited(val retryAfterSeconds: Int, val description: String) : TelegramCatalogResult<Nothing>()
    data class Unauthorized(val code: Int, val description: String) : TelegramCatalogResult<Nothing>()
    data class PermissionDenied(val code: Int, val description: String) : TelegramCatalogResult<Nothing>()
    data class InvalidResponse(val reason: String) : TelegramCatalogResult<Nothing>()
    data class ServerError(val code: Int, val description: String) : TelegramCatalogResult<Nothing>()
    data class NetworkError(val message: String, val cause: Throwable? = null) : TelegramCatalogResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.value
}

data class TelegramCatalogChatInfo(
    val id: Long,
    val type: String?,
    val title: String?,
    val pinnedMessage: TelegramPinnedMessage?
)

data class TelegramPinnedMessage(
    val messageId: Long,
    val date: Long,
    val document: TelegramDocument?
)

data class TelegramDocument(
    val fileId: String,
    val fileUniqueId: String?,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long?
)

data class TelegramFileInfo(
    val fileId: String,
    val fileUniqueId: String?,
    val fileSize: Long?,
    val filePath: String?
)

/**
 * Telegram Bot API client dedicated to catalog operations:
 * - Manifest uploading
 * - Master index & shard uploading
 * - Pinning and unpinning chat messages
 * - Verifying pinned messages via getChat
 * - Downloading catalog files via getFile + direct download
 *
 * Security & Reliability Guarantees:
 * - Bot tokens are NEVER logged, serialized, or exposed in exceptions.
 * - HTTP 429 returns retry_after duration.
 * - Download sizes are strictly bounded (default max 20 MB).
 */
open class TelegramCatalogApi(
    private val client: OkHttpClient = defaultHttpClient
) {

    /**
     * Uploads a document (JSON manifest, shard, or master index) to the target Telegram channel.
     */
    open suspend fun sendDocument(
        botToken: String,
        channelId: Long,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String = "application/json",
        messageThreadId: Long? = null,
        caption: String? = null,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogUploadResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext TelegramCatalogUploadResult.Unauthorized(401, "Bot token is empty")
        }
        if (channelId == 0L) {
            return@withContext TelegramCatalogUploadResult.InvalidResponse("Invalid channelId (0)")
        }

        val url = "$baseUrl/bot$botToken/sendDocument"
        val requestBody = fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", channelId.toString())
            .addFormDataPart("document", fileName, requestBody)
            .addFormDataPart("disable_content_type_detection", "true")

        if (messageThreadId != null) {
            bodyBuilder.addFormDataPart("message_thread_id", messageThreadId.toString())
        }
        if (caption != null) {
            bodyBuilder.addFormDataPart("caption", caption)
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.string()

            if (code == 429) {
                val retryAfter = parseRetryAfter(bodyString) ?: 30
                Log.w(TAG, "Rate limited on sendDocument (HTTP 429), retry after ${retryAfter}s")
                return@withContext TelegramCatalogUploadResult.RateLimited(
                    retryAfterSeconds = retryAfter,
                    description = "Telegram rate limited (retry after ${retryAfter}s)"
                )
            }
            if (code == 401) {
                return@withContext TelegramCatalogUploadResult.Unauthorized(code, "Unauthorized Telegram bot credentials")
            }
            if (code == 403) {
                return@withContext TelegramCatalogUploadResult.PermissionDenied(code, "Bot lacks permissions in channel $channelId")
            }
            if (code in 500..599) {
                return@withContext TelegramCatalogUploadResult.ServerError(code, "Telegram server error: HTTP $code")
            }
            if (!response.isSuccessful || bodyString.isNullOrBlank()) {
                val desc = parseDescription(bodyString) ?: "HTTP $code"
                return@withContext TelegramCatalogUploadResult.InvalidResponse("sendDocument failed with HTTP $code: $desc")
            }

            parseSendDocumentResponse(bodyString, channelId, fileBytes.size.toLong())

        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Network timeout during sendDocument: ${e.message}")
            TelegramCatalogUploadResult.NetworkError("Timeout during upload", e)
        } catch (e: InterruptedIOException) {
            TelegramCatalogUploadResult.NetworkError("Upload interrupted: ${e.message}", e)
        } catch (e: UnknownHostException) {
            TelegramCatalogUploadResult.NetworkError("Host unreachable", e)
        } catch (e: ConnectException) {
            TelegramCatalogUploadResult.NetworkError("Connection failed", e)
        } catch (e: IOException) {
            TelegramCatalogUploadResult.NetworkError(e.message ?: "I/O error", e)
        } catch (e: Exception) {
            TelegramCatalogUploadResult.NetworkError("Unexpected failure: ${e.message}", e)
        }
    }

    /**
     * Pins a message in the channel.
     */
    open suspend fun pinChatMessage(
        botToken: String,
        channelId: Long,
        messageId: Long,
        disableNotification: Boolean = true,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext TelegramCatalogResult.Unauthorized(401, "Bot token is empty")
        if (channelId == 0L || messageId <= 0L) {
            return@withContext TelegramCatalogResult.InvalidResponse("Invalid channelId ($channelId) or messageId ($messageId)")
        }

        val url = "$baseUrl/bot$botToken/pinChatMessage"
        val form = FormBody.Builder()
            .add("chat_id", channelId.toString())
            .add("message_id", messageId.toString())
            .add("disable_notification", disableNotification.toString())
            .build()

        val request = Request.Builder().url(url).post(form).build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.string()

            handleStandardResponse(code, bodyString) { json ->
                val result = json.optBoolean("result", false)
                TelegramCatalogResult.Success(result)
            }
        } catch (e: Exception) {
            mapException(e)
        }
    }

    /**
     * Unpins a message in the channel.
     * If messageId is null, unpins the most recent pinned message.
     */
    open suspend fun unpinChatMessage(
        botToken: String,
        channelId: Long,
        messageId: Long? = null,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext TelegramCatalogResult.Unauthorized(401, "Bot token is empty")
        if (channelId == 0L) return@withContext TelegramCatalogResult.InvalidResponse("Invalid channelId (0)")

        val url = "$baseUrl/bot$botToken/unpinChatMessage"
        val formBuilder = FormBody.Builder().add("chat_id", channelId.toString())
        if (messageId != null && messageId > 0L) {
            formBuilder.add("message_id", messageId.toString())
        }

        val request = Request.Builder().url(url).post(formBuilder.build()).build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.string()

            handleStandardResponse(code, bodyString) { json ->
                val result = json.optBoolean("result", false)
                TelegramCatalogResult.Success(result)
            }
        } catch (e: Exception) {
            mapException(e)
        }
    }

    /**
     * Gets chat information including the pinned message.
     */
    open suspend fun getChatInfo(
        botToken: String,
        channelId: Long,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<TelegramCatalogChatInfo> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext TelegramCatalogResult.Unauthorized(401, "Bot token is empty")
        if (channelId == 0L) return@withContext TelegramCatalogResult.InvalidResponse("Invalid channelId (0)")

        val url = "$baseUrl/bot$botToken/getChat?chat_id=$channelId"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.string()

            handleStandardResponse(code, bodyString) { json ->
                val resObj = json.optJSONObject("result")
                    ?: return@handleStandardResponse TelegramCatalogResult.InvalidResponse("Missing 'result' in getChat")

                val id = resObj.optLong("id", 0L)
                val type = resObj.optString("type", null)
                val title = resObj.optString("title", null)

                var pinnedMessage: TelegramPinnedMessage? = null
                val pinnedObj = resObj.optJSONObject("pinned_message")
                if (pinnedObj != null) {
                    val msgId = pinnedObj.optLong("message_id", 0L)
                    val date = pinnedObj.optLong("date", 0L)
                    var doc: TelegramDocument? = null
                    val docObj = pinnedObj.optJSONObject("document")
                    if (docObj != null) {
                        doc = TelegramDocument(
                            fileId = docObj.optString("file_id", ""),
                            fileUniqueId = docObj.optString("file_unique_id", null),
                            fileName = docObj.optString("file_name", null),
                            mimeType = docObj.optString("mime_type", null),
                            fileSize = if (docObj.has("file_size")) docObj.optLong("file_size") else null
                        )
                    }
                    pinnedMessage = TelegramPinnedMessage(
                        messageId = msgId,
                        date = date,
                        document = doc
                    )
                }

                TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = id,
                        type = type,
                        title = title,
                        pinnedMessage = pinnedMessage
                    )
                )
            }
        } catch (e: Exception) {
            mapException(e)
        }
    }

    /**
     * Gets file information (including file_path for download) given a fileId.
     */
    open suspend fun getFile(
        botToken: String,
        fileId: String,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<TelegramFileInfo> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext TelegramCatalogResult.Unauthorized(401, "Bot token is empty")
        if (fileId.isBlank()) return@withContext TelegramCatalogResult.InvalidResponse("fileId is empty")

        val url = "$baseUrl/bot$botToken/getFile?file_id=$fileId"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.string()

            handleStandardResponse(code, bodyString) { json ->
                val resObj = json.optJSONObject("result")
                    ?: return@handleStandardResponse TelegramCatalogResult.InvalidResponse("Missing 'result' in getFile")

                val resFileId = resObj.optString("file_id", "")
                val uniqueId = resObj.optString("file_unique_id", null)
                val size = if (resObj.has("file_size")) resObj.optLong("file_size") else null
                val filePath = resObj.optString("file_path", null)

                TelegramCatalogResult.Success(
                    TelegramFileInfo(
                        fileId = resFileId,
                        fileUniqueId = uniqueId,
                        fileSize = size,
                        filePath = filePath
                    )
                )
            }
        } catch (e: Exception) {
            mapException(e)
        }
    }

    /**
     * Downloads file bytes directly given a Telegram file_path.
     * Enforces strict [maxSizeBytes] bound to prevent out-of-memory.
     */
    open suspend fun downloadFile(
        botToken: String,
        filePath: String,
        maxSizeBytes: Long = MAX_DOWNLOAD_SIZE_BYTES,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<ByteArray> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext TelegramCatalogResult.Unauthorized(401, "Bot token is empty")
        if (filePath.isBlank()) return@withContext TelegramCatalogResult.InvalidResponse("filePath is empty")

        val url = "$baseUrl/file/bot$botToken/$filePath"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code

            if (code == 429) {
                val retryAfter = parseRetryAfter(response.body?.string()) ?: 30
                return@withContext TelegramCatalogResult.RateLimited(retryAfter, "Rate limited on file download")
            }
            if (code == 401) {
                return@withContext TelegramCatalogResult.Unauthorized(code, "Unauthorized token for file download")
            }
            if (code in 500..599) {
                return@withContext TelegramCatalogResult.ServerError(code, "Telegram download server error: $code")
            }
            if (!response.isSuccessful) {
                return@withContext TelegramCatalogResult.InvalidResponse("Download failed with HTTP $code")
            }

            val body = response.body
                ?: return@withContext TelegramCatalogResult.InvalidResponse("Empty download response body")

            val contentLength = body.contentLength()
            if (contentLength > maxSizeBytes) {
                return@withContext TelegramCatalogResult.InvalidResponse(
                    "File size ($contentLength bytes) exceeds maximum allowable limit ($maxSizeBytes bytes)"
                )
            }

            val bytes = body.bytes()
            if (bytes.size > maxSizeBytes) {
                return@withContext TelegramCatalogResult.InvalidResponse(
                    "Downloaded file bytes (${bytes.size}) exceeds limit ($maxSizeBytes)"
                )
            }

            TelegramCatalogResult.Success(bytes)
        } catch (e: Exception) {
            mapException(e)
        }
    }

    /**
     * Helper that resolves file_path via getFile and downloads the file bytes in a single call.
     */
    open suspend fun downloadFileByFileId(
        botToken: String,
        fileId: String,
        maxSizeBytes: Long = MAX_DOWNLOAD_SIZE_BYTES,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramCatalogResult<ByteArray> {
        val fileInfoResult = getFile(botToken, fileId, baseUrl)
        val fileInfo = when (fileInfoResult) {
            is TelegramCatalogResult.Success -> fileInfoResult.value
            is TelegramCatalogResult.RateLimited -> return fileInfoResult
            is TelegramCatalogResult.Unauthorized -> return fileInfoResult
            is TelegramCatalogResult.PermissionDenied -> return fileInfoResult
            is TelegramCatalogResult.InvalidResponse -> return fileInfoResult
            is TelegramCatalogResult.ServerError -> return fileInfoResult
            is TelegramCatalogResult.NetworkError -> return fileInfoResult
        }

        val filePath = fileInfo.filePath
        if (filePath.isNullOrBlank()) {
            return TelegramCatalogResult.InvalidResponse("Telegram did not provide file_path for fileId: $fileId")
        }

        return downloadFile(botToken, filePath, maxSizeBytes, baseUrl)
    }

    private fun parseSendDocumentResponse(
        jsonString: String,
        expectedChatId: Long,
        expectedSize: Long
    ): TelegramCatalogUploadResult {
        return try {
            val json = JSONObject(jsonString)
            val ok = json.optBoolean("ok", false)
            if (!ok) {
                val description = json.optString("description", "ok=false in response")
                val errorCode = json.optInt("error_code", 0)
                return TelegramCatalogUploadResult.InvalidResponse("Telegram API error $errorCode: $description")
            }

            val resultObj = json.optJSONObject("result")
                ?: return TelegramCatalogUploadResult.InvalidResponse("Missing result object in response")

            val messageId = resultObj.optLong("message_id", 0L)
            if (messageId <= 0L) {
                return TelegramCatalogUploadResult.InvalidResponse("Missing or invalid message_id: $messageId")
            }

            val chatObj = resultObj.optJSONObject("chat")
                ?: return TelegramCatalogUploadResult.InvalidResponse("Missing chat object in response")
            val chatId = chatObj.optLong("id", 0L)
            if (chatId != expectedChatId) {
                return TelegramCatalogUploadResult.InvalidResponse("Chat ID mismatch: expected $expectedChatId, got $chatId")
            }

            val docObj = resultObj.optJSONObject("document")
                ?: return TelegramCatalogUploadResult.InvalidResponse("Missing document object in sendDocument response")

            val fileId = docObj.optString("file_id", "")
            if (fileId.isBlank()) {
                return TelegramCatalogUploadResult.InvalidResponse("Empty file_id in returned document")
            }

            val docSize = if (docObj.has("file_size") && !docObj.isNull("file_size")) {
                docObj.optLong("file_size", -1L)
            } else {
                null
            }

            TelegramCatalogUploadResult.Success(
                fileId = fileId,
                messageId = messageId,
                fileSize = docSize ?: expectedSize,
                chatId = chatId
            )
        } catch (e: Exception) {
            TelegramCatalogUploadResult.InvalidResponse("Malformed JSON response: ${e.message}")
        }
    }

    private inline fun <T> handleStandardResponse(
        code: Int,
        bodyString: String?,
        onSuccess: (JSONObject) -> TelegramCatalogResult<T>
    ): TelegramCatalogResult<T> {
        if (code == 429) {
            val retryAfter = parseRetryAfter(bodyString) ?: 30
            return TelegramCatalogResult.RateLimited(retryAfter, "Rate limited (HTTP 429)")
        }
        if (code == 401) {
            return TelegramCatalogResult.Unauthorized(code, "Unauthorized Telegram credentials")
        }
        if (code == 403) {
            return TelegramCatalogResult.PermissionDenied(code, "Bot lacks permissions")
        }
        if (code in 500..599) {
            return TelegramCatalogResult.ServerError(code, "Telegram server error $code")
        }
        if (bodyString.isNullOrBlank()) {
            return TelegramCatalogResult.InvalidResponse("Empty response body (HTTP $code)")
        }

        return try {
            val json = JSONObject(bodyString)
            val ok = json.optBoolean("ok", false)
            if (!ok) {
                val description = json.optString("description", "ok=false")
                val errorCode = json.optInt("error_code", code)
                TelegramCatalogResult.InvalidResponse("Telegram API error $errorCode: $description")
            } else {
                onSuccess(json)
            }
        } catch (e: Exception) {
            TelegramCatalogResult.InvalidResponse("Malformed JSON response: ${e.message}")
        }
    }

    private fun <T> mapException(e: Exception): TelegramCatalogResult<T> {
        return when (e) {
            is SocketTimeoutException -> TelegramCatalogResult.NetworkError("Network timeout", e)
            is InterruptedIOException -> TelegramCatalogResult.NetworkError("Operation interrupted: ${e.message}", e)
            is UnknownHostException -> TelegramCatalogResult.NetworkError("Host unreachable", e)
            is ConnectException -> TelegramCatalogResult.NetworkError("Connection failed", e)
            is IOException -> TelegramCatalogResult.NetworkError(e.message ?: "I/O error", e)
            else -> TelegramCatalogResult.NetworkError("Unexpected error: ${e.message}", e)
        }
    }

    private fun parseRetryAfter(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = JSONObject(body)
            val params = json.optJSONObject("parameters")
            if (params != null && params.has("retry_after")) {
                params.getInt("retry_after")
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDescription(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optString("description")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "TelegramCatalogApi"
        const val MAX_DOWNLOAD_SIZE_BYTES = 20L * 1024 * 1024 // 20 MB hosted Telegram Bot API limit

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        val defaultInstance: TelegramCatalogApi by lazy {
            TelegramCatalogApi()
        }

        fun extractBotId(token: String?): Long {
            if (token.isNullOrBlank()) return 0L
            return token.substringBefore(":").toLongOrNull() ?: 0L
        }
    }
}
