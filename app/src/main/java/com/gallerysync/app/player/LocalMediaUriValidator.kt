package com.gallerysync.app.player

import android.net.Uri

/**
 * Validates that media URIs supplied for in-app video playback are local content
 * or file URIs (such as Android MediaStore content:// or local filesystem file:// URIs).
 *
 * Remote URLs (http/https), Telegram file IDs, empty URIs, and other unsupported schemes
 * are strictly rejected.
 */
object LocalMediaUriValidator {

    /**
     * Returns true if [uri] is a valid local media URI (scheme is "content" or "file").
     */
    fun isLocalMediaUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val uriString = uri.toString().trim()
        if (uriString.isEmpty()) return false

        val scheme = uri.scheme?.lowercase()?.trim() ?: return false
        if (scheme != "content" && scheme != "file") return false

        // Ensure scheme-specific part or path is non-empty
        val ssp = uri.schemeSpecificPart?.trim()
        val path = uri.path?.trim()
        return !ssp.isNullOrEmpty() || !path.isNullOrEmpty()
    }

    /**
     * Returns true if [uriString] can be parsed into a valid local media URI.
     */
    fun isLocalMediaUri(uriString: String): Boolean {
        val trimmed = uriString.trim()
        if (trimmed.isEmpty()) return false
        return try {
            isLocalMediaUri(Uri.parse(trimmed))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates that [uri] is a local media URI, returning [uri] on success
     * or throwing [IllegalArgumentException] if invalid.
     */
    fun requireLocalMediaUri(uri: Uri): Uri {
        require(isLocalMediaUri(uri)) {
            "Invalid or non-local media URI '$uri'. GallerySync video playback requires local content:// or file:// URIs."
        }
        return uri
    }
}
