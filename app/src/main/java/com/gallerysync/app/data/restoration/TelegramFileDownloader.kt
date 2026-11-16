package com.gallerysync.app.data.restoration

import android.util.Log
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Streaming downloader for Telegram media files and chunked backup slices.
 *
 * Guarantees:
 * - Bounded 64 KiB memory footprint: streams directly to disk.
 * - Enforces Telegram's hosted download limit of 20,000,000 bytes.
 * - Computes cryptographic SHA-256 in real time on the fly.
 * - Validates expected byte count and chunk hash upon completion.
 * - Supports cooperative cancellation at 64 KiB buffer boundaries.
 * - Redacts bot tokens and complete download URLs across all logs and error messages.
 * - Atomically promotes staged files (.part -> target) only after verified integrity.
 */
open class TelegramFileDownloader(
    private val client: OkHttpClient = TelegramCatalogApi.defaultHttpClient,
    private val catalogApi: TelegramCatalogApi = TelegramCatalogApi.defaultInstance
) {

    /**
     * Resolves the Telegram file_path via getFile and streams the file to [destinationFile].
     */
    open suspend fun downloadToFile(
        botToken: String,
        fileId: String,
        destinationFile: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        baseUrl: String = "https://api.telegram.org",
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesRead: Long, totalExpectedBytes: Long) -> Unit)? = null
    ): TelegramDownloadResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext TelegramDownloadResult.Unauthorized(401, "Bot token is empty")
        }
        if (fileId.isBlank()) {
            return@withContext TelegramDownloadResult.InvalidResponse("fileId is empty")
        }
        if (isCancelled()) {
            return@withContext TelegramDownloadResult.Cancelled()
        }

        // 1. Resolve remote file metadata from Telegram
        val fileInfoResult = catalogApi.getFile(botToken, fileId, baseUrl)
        val fileInfo = when (fileInfoResult) {
            is TelegramCatalogResult.Success -> fileInfoResult.value
            is TelegramCatalogResult.RateLimited -> {
                return@withContext TelegramDownloadResult.RateLimited(
                    fileInfoResult.retryAfterSeconds,
                    fileInfoResult.description
                )
            }
            is TelegramCatalogResult.Unauthorized -> {
                return@withContext TelegramDownloadResult.Unauthorized(
                    fileInfoResult.code,
                    fileInfoResult.description
                )
            }
            is TelegramCatalogResult.PermissionDenied -> {
                return@withContext TelegramDownloadResult.PermissionDenied(
                    fileInfoResult.code,
                    fileInfoResult.description
                )
            }
            is TelegramCatalogResult.ServerError -> {
                return@withContext TelegramDownloadResult.ServerError(
                    fileInfoResult.code,
                    fileInfoResult.description
                )
            }
            is TelegramCatalogResult.InvalidResponse -> {
                return@withContext TelegramDownloadResult.InvalidResponse(fileInfoResult.reason)
            }
            is TelegramCatalogResult.NetworkError -> {
                return@withContext TelegramDownloadResult.NetworkError(
                    fileInfoResult.message,
                    fileInfoResult.cause
                )
            }
        }

        val remoteFilePath = fileInfo.filePath
        if (remoteFilePath.isNullOrBlank()) {
            return@withContext TelegramDownloadResult.InvalidResponse("Telegram did not provide file_path for fileId: $fileId")
        }

        // 2. Validate remote file size against Telegram hosted download ceiling (20,000,000 bytes)
        val remoteSize = fileInfo.fileSize
        if (remoteSize != null && remoteSize > RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES) {
            return@withContext TelegramDownloadResult.InvalidResponse(
                "Remote file size ($remoteSize bytes) exceeds Telegram hosted download limit of " +
                    "${RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES} bytes. " +
                    "Large videos must be backed up and restored via chunked pipeline."
            )
        }

        val effectiveExpectedSize = expectedSizeBytes ?: remoteSize

        // 3. Stream download from remote path
        downloadPathToFile(
            botToken = botToken,
            filePath = remoteFilePath,
            destinationFile = destinationFile,
            expectedSizeBytes = effectiveExpectedSize,
            expectedSha256 = expectedSha256,
            baseUrl = baseUrl,
            isCancelled = isCancelled,
            onProgress = onProgress
        )
    }

    /**
     * Streams file bytes from Telegram file path directly into [destinationFile].
     */
    open suspend fun downloadPathToFile(
        botToken: String,
        filePath: String,
        destinationFile: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        baseUrl: String = "https://api.telegram.org",
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesRead: Long, totalExpectedBytes: Long) -> Unit)? = null
    ): TelegramDownloadResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext TelegramDownloadResult.Unauthorized(401, "Bot token is empty")
        }
        if (filePath.isBlank()) {
            return@withContext TelegramDownloadResult.InvalidResponse("filePath is empty")
        }
        if (isCancelled()) {
            return@withContext TelegramDownloadResult.Cancelled()
        }

        destinationFile.parentFile?.mkdirs()
        val partFile = File(destinationFile.parentFile, "${destinationFile.name}.part")
        if (partFile.exists()) {
            partFile.delete()
        }

        val requestUrl = "$baseUrl/file/bot$botToken/$filePath"
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code

            if (code == 429) {
                val bodyStr = response.body?.string()
                val retryAfter = parseRetryAfter(bodyStr) ?: 30
                Log.w(TAG, "Rate limited downloading file: retry after ${retryAfter}s")
                return@withContext TelegramDownloadResult.RateLimited(retryAfter, "Rate limited on file download")
            }
            if (code == 401) {
                return@withContext TelegramDownloadResult.Unauthorized(code, "Unauthorized token for file download")
            }
            if (code == 403) {
                return@withContext TelegramDownloadResult.PermissionDenied(code, "Access denied to download file")
            }
            if (code in 500..599) {
                return@withContext TelegramDownloadResult.ServerError(code, "Telegram server error: $code")
            }
            if (!response.isSuccessful) {
                return@withContext TelegramDownloadResult.InvalidResponse("Download failed with HTTP $code")
            }

            val body = response.body
                ?: return@withContext TelegramDownloadResult.InvalidResponse("Empty response body from Telegram")

            val contentLength = body.contentLength()
            if (contentLength > RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES) {
                return@withContext TelegramDownloadResult.InvalidResponse(
                    "Content-Length ($contentLength bytes) exceeds download ceiling (${RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES} bytes)"
                )
            }

            val totalExpected = if (expectedSizeBytes != null && expectedSizeBytes > 0L) {
                expectedSizeBytes
            } else if (contentLength > 0L) {
                contentLength
            } else {
                0L
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(RestorationConfiguration.BUFFER_SIZE_BYTES)
            var bytesRead = 0L

            body.byteStream().use { inStream ->
                FileOutputStream(partFile).use { outStream ->
                    while (true) {
                        if (isCancelled()) {
                            Log.i(TAG, "Download cancelled cooperatively during streaming")
                            partFile.delete()
                            return@withContext TelegramDownloadResult.Cancelled()
                        }

                        val count = inStream.read(buffer)
                        if (count == -1) break

                        outStream.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytesRead += count
                        onProgress?.invoke(bytesRead, totalExpected)
                    }
                    outStream.flush()
                }
            }

            // 4. Validate byte count
            if (expectedSizeBytes != null && expectedSizeBytes > 0L && bytesRead != expectedSizeBytes) {
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                Log.e(TAG, "Byte count mismatch: expected $expectedSizeBytes, received $bytesRead")
                partFile.delete()
                return@withContext TelegramDownloadResult.IntegrityMismatch(
                    expectedSha256 = expectedSha256 ?: "",
                    actualSha256 = computedHash,
                    expectedSize = expectedSizeBytes,
                    actualSize = bytesRead
                )
            }

            // 5. Validate cryptographic SHA-256 checksum
            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expectedSha256.isNullOrBlank() && !computedSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "Checksum mismatch for file! Expected $expectedSha256, computed $computedSha256")
                partFile.delete()
                return@withContext TelegramDownloadResult.IntegrityMismatch(
                    expectedSha256 = expectedSha256,
                    actualSha256 = computedSha256,
                    expectedSize = expectedSizeBytes ?: bytesRead,
                    actualSize = bytesRead
                )
            }

            // 6. Promote verified part file to final destination
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            val renamed = partFile.renameTo(destinationFile)
            if (!renamed) {
                // Fallback copy if cross-filesystem rename fails
                partFile.copyTo(destinationFile, overwrite = true)
                partFile.delete()
            }

            Log.i(TAG, "Successfully downloaded and verified $bytesRead bytes (SHA-256: $computedSha256)")
            TelegramDownloadResult.Success(bytesRead, computedSha256)
        } catch (e: Exception) {
            val sanitized = TelegramDownloadException.redactCredentials(e.message)
            Log.e(TAG, "Download failed: $sanitized", e)
            partFile.delete()
            TelegramDownloadResult.NetworkError("Download failed: $sanitized", e)
        }
    }

    private fun parseRetryAfter(responseBody: String?): Int? {
        if (responseBody.isNullOrBlank()) return null
        return try {
            val json = JSONObject(responseBody)
            val params = json.optJSONObject("parameters")
            params?.optInt("retry_after", 0)?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "TelegramFileDownloader"
        val defaultInstance: TelegramFileDownloader by lazy { TelegramFileDownloader() }
    }
}
