package com.gallerysync.app.data.restoration

import java.io.IOException

/**
 * Exceptions thrown during Telegram media downloads, with guaranteed redaction of bot tokens and full URLs.
 */
sealed class TelegramDownloadException(
    rawMessage: String,
    cause: Throwable? = null
) : IOException(redactCredentials(rawMessage), cause) {

    class Unauthorized(message: String) : TelegramDownloadException(message)
    class RateLimited(val retryAfterSeconds: Int, message: String) : TelegramDownloadException(message)
    class ServerError(val code: Int, message: String) : TelegramDownloadException(message)
    class FileSizeExceeded(message: String) : TelegramDownloadException(message)
    class IntegrityFailure(message: String) : TelegramDownloadException(message)
    class StorageFull(message: String) : TelegramDownloadException(message)
    class Cancelled(message: String = "Download operation cancelled") : TelegramDownloadException(message)

    companion object {
        private val TOKEN_REGEX = Regex("""bot\d+:[A-Za-z0-9_-]+""")
        private val FILE_URL_REGEX = Regex("""https://api\.telegram\.org/file/bot[^/]+""")

        fun redactCredentials(input: String?): String {
            if (input.isNullOrBlank()) return ""
            var sanitized = TOKEN_REGEX.replace(input, "bot[REDACTED]")
            sanitized = FILE_URL_REGEX.replace(sanitized, "https://api.telegram.org/file/bot[REDACTED]")
            return sanitized
        }
    }
}
