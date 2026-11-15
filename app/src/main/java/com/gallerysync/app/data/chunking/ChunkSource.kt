package com.gallerysync.app.data.chunking

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.channels.FileChannel

/**
 * Abstraction for reading media content from Android ContentResolver URIs or local files.
 *
 * Provides:
 * - Metadata querying (file size, modified timestamp, display name, MIME type).
 * - Dual-path capability discovery: checks whether the source provides a seekable FileChannel.
 * - Safe stream and file descriptor lifecycle management.
 */
interface ChunkSource : Closeable {
    val uri: Uri

    suspend fun getMetadata(): SourceMetadata
    suspend fun validate(): SourceValidationResult
    suspend fun openStream(): InputStream
    suspend fun openSeekableChannel(): FileChannel?
    fun isSeekable(): Boolean
}

/**
 * Android [ContentResolver]-backed [ChunkSource].
 * Discovers seekability dynamically without assuming all URIs support seekable FileDescriptors.
 */
class UriChunkSource(
    private val context: Context,
    override val uri: Uri
) : ChunkSource {

    private var openPfd: ParcelFileDescriptor? = null
    private var openFileInputStream: FileInputStream? = null

    override suspend fun getMetadata(): SourceMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName: String? = null
        var fileSize: Long = -1L
        var dateModified: Long? = null
        val mimeType = resolver.getType(uri)

        // Try querying OpenableColumns and MediaColumns
        try {
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                "date_modified"
            )
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                    val modIdx = cursor.getColumnIndex("date_modified")
                    if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                        dateModified = cursor.getLong(modIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query ContentResolver projection for $uri: ${e.message}")
        }

        // Fallback: If size is still -1, attempt statSize from ParcelFileDescriptor
        if (fileSize < 0L) {
            try {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    fileSize = pfd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect statSize for $uri: ${e.message}")
            }
        }

        SourceMetadata(
            uri = uri,
            fileSize = maxOf(0L, fileSize),
            dateModified = dateModified,
            displayName = displayName,
            mimeType = mimeType
        )
    }

    override suspend fun validate(): SourceValidationResult = withContext(Dispatchers.IO) {
        try {
            val metadata = getMetadata()
            if (metadata.fileSize <= 0L) {
                return@withContext SourceValidationResult.EmptySource(uri, metadata.fileSize)
            }
            // Verify stream can be opened
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext SourceValidationResult.NotFound(uri, "ContentResolver returned null InputStream")
            stream.close()
            SourceValidationResult.Valid
        } catch (e: SecurityException) {
            SourceValidationResult.PermissionDenied(uri, e.message ?: "Permission denied")
        } catch (e: FileNotFoundException) {
            SourceValidationResult.NotFound(uri, e.message ?: "File not found")
        } catch (e: Exception) {
            SourceValidationResult.Inaccessible(uri, e.message ?: "Unknown error")
        }
    }

    override suspend fun openStream(): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open InputStream for $uri")
    }

    override suspend fun openSeekableChannel(): FileChannel? = withContext(Dispatchers.IO) {
        try {
            close() // Close previous if any
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            openPfd = pfd
            val fis = FileInputStream(pfd.fileDescriptor)
            openFileInputStream = fis
            val channel = fis.channel

            // Probe seekability
            channel.position(channel.position())
            channel
        } catch (e: Exception) {
            Log.d(TAG, "FileDescriptor for $uri is non-seekable: ${e.message}")
            close()
            null
        }
    }

    override fun isSeekable(): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    ch.position(ch.position())
                    true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try {
            openFileInputStream?.close()
        } catch (_: Exception) {}
        openFileInputStream = null

        try {
            openPfd?.close()
        } catch (_: Exception) {}
        openPfd = null
    }

    companion object {
        private const val TAG = "UriChunkSource"
    }
}

/**
 * Local [File]-backed [ChunkSource], primarily used for disk files and unit testing.
 * Always seekable.
 */
class FileChunkSource(val file: File) : ChunkSource {
    override val uri: Uri = Uri.fromFile(file)
    private var openFis: FileInputStream? = null

    override suspend fun getMetadata(): SourceMetadata = withContext(Dispatchers.IO) {
        SourceMetadata(
            uri = uri,
            fileSize = file.length(),
            dateModified = file.lastModified(),
            displayName = file.name,
            mimeType = "video/mp4"
        )
    }

    override suspend fun validate(): SourceValidationResult = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext SourceValidationResult.NotFound(uri, "File does not exist: ${file.absolutePath}")
        }
        if (file.length() <= 0L) {
            return@withContext SourceValidationResult.EmptySource(uri, file.length())
        }
        SourceValidationResult.Valid
    }

    override suspend fun openStream(): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(file)
    }

    override suspend fun openSeekableChannel(): FileChannel = withContext(Dispatchers.IO) {
        close()
        val fis = FileInputStream(file)
        openFis = fis
        fis.channel
    }

    override fun isSeekable(): Boolean = true

    override fun close() {
        try {
            openFis?.close()
        } catch (_: Exception) {}
        openFis = null
    }
}

/**
 * Non-seekable stream [ChunkSource] used to test and execute Path B fallback behavior.
 */
class StreamChunkSource(
    override val uri: Uri,
    private val metadata: SourceMetadata,
    private val streamProvider: () -> InputStream
) : ChunkSource {

    override suspend fun getMetadata(): SourceMetadata = metadata

    override suspend fun validate(): SourceValidationResult {
        if (metadata.fileSize <= 0L) {
            return SourceValidationResult.EmptySource(uri, metadata.fileSize)
        }
        return SourceValidationResult.Valid
    }

    override suspend fun openStream(): InputStream = streamProvider()

    override suspend fun openSeekableChannel(): FileChannel? = null

    override fun isSeekable(): Boolean = false

    override fun close() {}
}
