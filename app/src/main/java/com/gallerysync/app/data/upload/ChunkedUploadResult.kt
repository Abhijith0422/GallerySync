package com.gallerysync.app.data.upload

/**
 * Result hierarchy for [ChunkedUploadEngine] operations.
 */
sealed class ChunkedUploadResult {
    data class Success(
        val backupId: String,
        val completedChunks: Int,
        val totalChunks: Int,
        val isAllChunksUploaded: Boolean
    ) : ChunkedUploadResult()

    data class RateLimited(
        val backupId: String,
        val retryAfterSeconds: Int,
        val scheduledRetryAt: Long
    ) : ChunkedUploadResult()

    data class SourceModified(
        val backupId: String,
        val reason: String
    ) : ChunkedUploadResult()

    data class ConfigurationMismatch(
        val backupId: String,
        val reason: String
    ) : ChunkedUploadResult()

    data class NetworkUnavailable(
        val backupId: String,
        val reason: String
    ) : ChunkedUploadResult()

    data class TransientError(
        val backupId: String,
        val message: String,
        val scheduledRetryAt: Long
    ) : ChunkedUploadResult()

    data class PermanentError(
        val backupId: String,
        val message: String,
        val cause: Throwable? = null
    ) : ChunkedUploadResult()

    data class Cancelled(
        val backupId: String,
        val message: String
    ) : ChunkedUploadResult()

    data class Yielded(
        val backupId: String,
        val completedChunks: Int,
        val totalChunks: Int,
        val reason: String
    ) : ChunkedUploadResult()
}
