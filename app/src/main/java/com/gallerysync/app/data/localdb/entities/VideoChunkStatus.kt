package com.gallerysync.app.data.localdb.entities

/**
 * Lifecycle states of an individual 16 MiB video chunk.
 *
 * Transitions:
 * PENDING -> UPLOADING -> DONE
 * UPLOADING -> RETRY_WAIT (transient error / 429 rate limit with scheduled retry timestamp)
 * RETRY_WAIT -> PENDING (upon reaching scheduled retry timestamp)
 * UPLOADING -> FAILED (terminal failure / maximum retries exceeded)
 */
enum class VideoChunkStatus {
    PENDING,
    UPLOADING,
    DONE,
    FAILED,
    RETRY_WAIT;

    companion object {
        fun fromString(value: String?): VideoChunkStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}
