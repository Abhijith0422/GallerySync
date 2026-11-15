package com.gallerysync.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Computes definitive full-file SHA-256 hashes using streaming reads and a bounded 64 KB buffer.
 * Never loads entire media files into memory, guarantees safe stream closure,
 * and preserves coroutine cancellation.
 */
object ContentHasher {

    private const val TAG = "ContentHasher"
    const val BUFFER_SIZE = 64 * 1024 // Bounded 64 KB streaming buffer

    /**
     * Computes definitive full-file SHA-256 hash of file content pointed to by [uri].
     * Returns hex-encoded 64-character lowercase SHA-256 string, or null on failure.
     */
    suspend fun computeHash(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val inputStream = resolver.openInputStream(uri) ?: run {
                Log.w(TAG, "ContentResolver returned null InputStream for $uri")
                return@withContext null
            }
            computeHashFromStream(inputStream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute full-file hash for $uri: ${e.message}", e)
            null
        }
    }

    /**
     * Streams full contents from [inputStream] through SHA-256 digest with bounded 64 KB buffer.
     * Guarantees stream closure and honors coroutine cancellation.
     */
    suspend fun computeHashFromStream(inputStream: InputStream): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        inputStream.use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                coroutineContext.ensureActive()
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes SHA-256 hash of a byte array.
     */
    fun computeHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
