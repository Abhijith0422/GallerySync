package com.gallerysync.app.data.restoration

import androidx.annotation.Keep

@Keep
sealed class TelegramDownloadResult {
    data class Success(
        val bytesRead: Long,
        val sha256: String
    ) : TelegramDownloadResult()

    data class RateLimited(
        val retryAfterSeconds: Int,
        val description: String
    ) : TelegramDownloadResult()

    data class Unauthorized(
        val code: Int,
        val description: String
    ) : TelegramDownloadResult()

    data class PermissionDenied(
        val code: Int,
        val description: String
    ) : TelegramDownloadResult()

    data class InvalidResponse(
        val reason: String
    ) : TelegramDownloadResult()

    data class ServerError(
        val code: Int,
        val description: String
    ) : TelegramDownloadResult()

    data class NetworkError(
        val message: String,
        val cause: Throwable? = null
    ) : TelegramDownloadResult()

    data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long
    ) : TelegramDownloadResult()

    data class IntegrityMismatch(
        val expectedSha256: String,
        val actualSha256: String,
        val expectedSize: Long,
        val actualSize: Long
    ) : TelegramDownloadResult()

    data class Cancelled(
        val reason: String = "Download cancelled"
    ) : TelegramDownloadResult()
}
