package com.gallerysync.app.data.localdb.entities

/**
 * Lifecycle states of a chunked video backup.
 *
 * Transitions:
 * PENDING -> HASHING -> UPLOADING -> VERIFYING -> DONE
 * Any non-terminal state -> FAILED (on unrecoverable error)
 * Any non-terminal state -> CANCELLED (on user cancellation)
 */
enum class ChunkedBackupStatus {
    PENDING,
    HASHING,
    UPLOADING,
    VERIFYING,
    DONE,
    FAILED,
    CANCELLED;

    companion object {
        fun fromString(value: String?): ChunkedBackupStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}
