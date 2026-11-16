package com.gallerysync.app.data.restoration

import androidx.annotation.Keep

@Keep
enum class RestorationStatus {
    PENDING,
    VALIDATING,
    DOWNLOADING,
    VERIFYING,
    RECONSTRUCTING,
    AWAITING_DESTINATION,
    WRITING_OUTPUT,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = !isTerminal

    fun userFriendlyDescription(): String = when (this) {
        PENDING -> "Preparing restoration"
        VALIDATING -> "Validating manifest"
        DOWNLOADING -> "Downloading chunks"
        VERIFYING -> "Verifying chunk integrity"
        RECONSTRUCTING -> "Reconstructing video"
        AWAITING_DESTINATION -> "Waiting for destination"
        WRITING_OUTPUT -> "Saving restored video"
        COMPLETED -> "Restoration complete"
        FAILED -> "Restoration failed"
        CANCELLED -> "Restoration cancelled"
    }
}
