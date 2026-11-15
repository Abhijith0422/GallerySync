package com.gallerysync.app.data.chunking

/**
 * Detects modifications to the source media file across hashing or slicing operations.
 */
object SourceModificationDetector {

    sealed class ChangeResult {
        data object Unchanged : ChangeResult()
        data class Modified(
            val field: String,
            val expected: Any?,
            val actual: Any?,
            val message: String
        ) : ChangeResult()
    }

    /**
     * Compares an initial snapshot against the currently observed metadata.
     */
    fun detectChanges(initial: SourceMetadata, current: SourceMetadata): ChangeResult {
        if (initial.fileSize != current.fileSize) {
            return ChangeResult.Modified(
                field = "fileSize",
                expected = initial.fileSize,
                actual = current.fileSize,
                message = "File size changed from ${initial.fileSize} to ${current.fileSize} bytes"
            )
        }

        if (initial.dateModified != null && current.dateModified != null && initial.dateModified != current.dateModified) {
            return ChangeResult.Modified(
                field = "dateModified",
                expected = initial.dateModified,
                actual = current.dateModified,
                message = "Modification date changed from ${initial.dateModified} to ${current.dateModified}"
            )
        }

        if (initial.displayName != null && current.displayName != null && initial.displayName != current.displayName) {
            return ChangeResult.Modified(
                field = "displayName",
                expected = initial.displayName,
                actual = current.displayName,
                message = "Display name changed from '${initial.displayName}' to '${current.displayName}'"
            )
        }

        return ChangeResult.Unchanged
    }
}
