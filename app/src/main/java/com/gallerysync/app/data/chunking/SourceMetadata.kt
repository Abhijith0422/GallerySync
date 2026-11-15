package com.gallerysync.app.data.chunking

import android.net.Uri

/**
 * Snapshot of media source metadata used for chunk calculation and source immutability verification.
 */
data class SourceMetadata(
    val uri: Uri,
    val fileSize: Long,
    val dateModified: Long? = null,
    val displayName: String? = null,
    val mimeType: String? = null
)

/**
 * Result of validating a media source before reading or hashing.
 */
sealed class SourceValidationResult {
    data object Valid : SourceValidationResult()
    data class EmptySource(val uri: Uri, val size: Long) : SourceValidationResult()
    data class NotFound(val uri: Uri, val message: String) : SourceValidationResult()
    data class PermissionDenied(val uri: Uri, val message: String) : SourceValidationResult()
    data class Inaccessible(val uri: Uri, val reason: String) : SourceValidationResult()
}
