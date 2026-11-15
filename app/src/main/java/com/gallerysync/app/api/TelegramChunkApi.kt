package com.gallerysync.app.api

import android.util.Log
import com.gallerysync.app.data.chunking.ChunkIntegrityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Result hierarchy for Telegram chunk document uploads.
 */
sealed class TelegramChunkUploadResult {
    data class Success(
        val fileId: String,
        val messageId: Long,
        val fileSize: Long,
        val chatId: Long
    ) : TelegramChunkUploadResult()

    data class RateLimited(val retryAfterSeconds: Int, val description: String) : TelegramChunkUploadResult()
    data class Unauthorized(val code: Int, val description: String) : TelegramChunkUploadResult()
    data class PermissionDenied(val code: Int, val description: String) : TelegramChunkUploadResult()
    data class InvalidResponse(val reason: String) : TelegramChunkUploadResult()
    data class ServerError(val code: Int, val description: String) : TelegramChunkUploadResult()
    data class NetworkError(val message: String, val cause: Throwable? = null) : TelegramChunkUploadResult()
    data class IntegrityError(val message: String, val cause: Throwable? = null) : TelegramChunkUploadResult()
    data class Cancelled(val message: String) : TelegramChunkUploadResult()
}

/**
 * Dedicated API handler for chunked Telegram document uploads.
 * Reuses OkHttp and enforces strict Telegram response validation.
 *
 * Guarantees:
 * - Bot tokens are NEVER logged, serialized, or exposed in exceptions.
 * - HTTP 200 responses are strictly verified: ok==true, valid document, matching chat ID, matching file size.
 * - HTTP 429 returns server-specified retry_after duration.
 * - In-flight byte and SHA-256 integrity failures are caught and surfaced as [TelegramChunkUploadResult.IntegrityError].
 */
open class TelegramChunkApi(
    private val client: OkHttpClient = defaultHttpClient
) {

    open suspend fun sendChunkDocument(
        botToken: String,
        channelId: Long,
        chunkRequestBody: RequestBody,
        fileName: String,
        expectedSize: Long,
        messageThreadId: Long? = null,
        caption: String? = null,
        baseUrl: String = "https://api.telegram.org"
    ): TelegramChunkUploadResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext TelegramChunkUploadResult.Unauthorized(401, "Bot token is empty")
        }
        if (channelId == 0L) {
            return@withContext TelegramChunkUploadResult.InvalidResponse("Invalid channelId (0)")
        }

        val url = "$baseUrl/bot$botToken/sendDocument"

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", channelId.toString())
            .addFormDataPart("document", fileName, chunkRequestBody)
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
                Log.w(TAG, "Telegram rate limited (HTTP 429), retry after ${retryAfter}s")
                return@withContext TelegramChunkUploadResult.RateLimited(
                    retryAfterSeconds = retryAfter,
                    description = "Telegram rate limited (retry after ${retryAfter}s)"
                )
            }

            if (code == 401) {
                return@withContext TelegramChunkUploadResult.Unauthorized(code, "Unauthorized Telegram bot credentials")
            }

            if (code == 403) {
                return@withContext TelegramChunkUploadResult.PermissionDenied(code, "Bot lacks permissions in channel $channelId")
            }

            if (code in 500..599) {
                return@withContext TelegramChunkUploadResult.ServerError(code, "Telegram server error: HTTP $code")
            }

            if (!response.isSuccessful || bodyString.isNullOrBlank()) {
                val desc = parseDescription(bodyString) ?: "HTTP $code"
                return@withContext TelegramChunkUploadResult.InvalidResponse("Telegram request failed with HTTP $code: $desc")
            }

            // Parse response body and validate all required fields
            validateAndExtractResponse(bodyString, channelId, expectedSize)

        } catch (e: ChunkIntegrityException) {
            Log.e(TAG, "Chunk integrity mismatch during upload: ${e.message}")
            TelegramChunkUploadResult.IntegrityError(e.message ?: "Integrity error", e)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Network timeout during chunk upload: ${e.message}")
            TelegramChunkUploadResult.NetworkError("Timeout during upload", e)
        } catch (e: InterruptedIOException) {
            Log.w(TAG, "Upload interrupted: ${e.message}")
            TelegramChunkUploadResult.Cancelled("Upload interrupted: ${e.message}")
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Cannot resolve host: ${e.message}")
            TelegramChunkUploadResult.NetworkError("Host unreachable", e)
        } catch (e: ConnectException) {
            Log.w(TAG, "Connection failed: ${e.message}")
            TelegramChunkUploadResult.NetworkError("Connection failed", e)
        } catch (e: IOException) {
            Log.w(TAG, "I/O error during chunk upload: ${e.message}")
            TelegramChunkUploadResult.NetworkError(e.message ?: "I/O error", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during chunk upload", e)
            TelegramChunkUploadResult.NetworkError("Unexpected failure: ${e.message}", e)
        }
    }

    private fun validateAndExtractResponse(
        jsonString: String,
        expectedChatId: Long,
        expectedSize: Long
    ): TelegramChunkUploadResult {
        return try {
            val json = JSONObject(jsonString)
            val ok = json.optBoolean("ok", false)
            if (!ok) {
                val description = json.optString("description", "ok=false in response")
                val errorCode = json.optInt("error_code", 0)
                return TelegramChunkUploadResult.InvalidResponse("Telegram API error $errorCode: $description")
            }

            val resultObj = json.optJSONObject("result")
                ?: return TelegramChunkUploadResult.InvalidResponse("Missing result object in response")

            val messageId = resultObj.optLong("message_id", 0L)
            if (messageId <= 0L) {
                return TelegramChunkUploadResult.InvalidResponse("Missing or invalid message_id in response: $messageId")
            }

            val chatObj = resultObj.optJSONObject("chat")
                ?: return TelegramChunkUploadResult.InvalidResponse("Missing chat object in response")
            val chatId = chatObj.optLong("id", 0L)
            if (chatId != expectedChatId) {
                return TelegramChunkUploadResult.InvalidResponse("Chat ID mismatch: expected $expectedChatId, got $chatId")
            }

            val docObj = resultObj.optJSONObject("document")
                ?: return TelegramChunkUploadResult.InvalidResponse("Missing document object in sendDocument response")

            val fileId = docObj.optString("file_id", "")
            if (fileId.isBlank()) {
                return TelegramChunkUploadResult.InvalidResponse("Empty file_id in returned document")
            }

            val docSize = if (docObj.has("file_size") && !docObj.isNull("file_size")) {
                docObj.optLong("file_size", -1L)
            } else {
                null
            }

            if (docSize != null && docSize != expectedSize) {
                return TelegramChunkUploadResult.InvalidResponse(
                    "Returned document size ($docSize) does not match expected chunk size ($expectedSize)"
                )
            }

            TelegramChunkUploadResult.Success(
                fileId = fileId,
                messageId = messageId,
                fileSize = docSize ?: expectedSize,
                chatId = chatId
            )
        } catch (e: Exception) {
            TelegramChunkUploadResult.InvalidResponse("Malformed JSON response from Telegram: ${e.message}")
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
        private const val TAG = "TelegramChunkApi"

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Extracts bot ID from a standard Telegram bot token (e.g. 123456789:ABCdef... -> 123456789L).
         */
        fun extractBotId(token: String?): Long {
            if (token.isNullOrBlank()) return 0L
            return token.substringBefore(":").toLongOrNull() ?: 0L
        }
    }
}
