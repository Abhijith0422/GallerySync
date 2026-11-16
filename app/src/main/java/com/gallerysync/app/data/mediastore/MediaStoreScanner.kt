package com.gallerysync.app.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.dao.PhotoDao
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.utils.MediaAccessState
import com.gallerysync.app.utils.PermissionManager
import com.gallerysync.app.utils.getExtFromMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Unified MediaStore scanner and indexer for images and videos.
 * Queries [MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)] with
 * permission-aware selection, batched retrieval, and safe Room insertion.
 */
object MediaStoreScanner {

    private const val TAG = "MediaStoreScanner"

    val COLLECTION_URI: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    val PROJECTION: Array<String> = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
    )

    /**
     * Builds selection clause and args based on [MediaAccessState] and optional timestamp filter.
     */
    fun buildSelection(
        accessState: MediaAccessState,
        sinceTimestampSeconds: Long? = null
    ): Pair<String?, Array<String>?> {
        val typeClause: String
        val typeArgs: Array<String>

        when (accessState) {
            is MediaAccessState.FullAccess, is MediaAccessState.PartialAccess -> {
                typeClause = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
                typeArgs = arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            }
            is MediaAccessState.ImagesOnlyAccess -> {
                typeClause = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
                typeArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            }
            is MediaAccessState.VideosOnlyAccess -> {
                typeClause = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
                typeArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }
            is MediaAccessState.NoAccess -> {
                return null to null
            }
        }

        return if (sinceTimestampSeconds != null && sinceTimestampSeconds > 0L) {
            val combinedClause = "$typeClause AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?"
            val combinedArgs = typeArgs + sinceTimestampSeconds.toString()
            combinedClause to combinedArgs
        } else {
            typeClause to typeArgs
        }
    }

    /**
     * Safely parses a single cursor row into a [ScannedMediaItem].
     */
    fun parseCursorRow(cursor: Cursor, deviceId: String?): ScannedMediaItem? {
        val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
        if (idIdx == -1) return null
        val id = runCatching { cursor.getLong(idIdx) }.getOrNull() ?: return null
        if (id <= 0) return null

        val mediaTypeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val mimeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
        val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
        val addedIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
        val modIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val takenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val durIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
        val bucketIdIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
        val bucketNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

        val rawMediaType = if (mediaTypeIdx != -1 && !cursor.isNull(mediaTypeIdx)) {
            runCatching { cursor.getInt(mediaTypeIdx) }.getOrDefault(0)
        } else 0

        val isVideo = rawMediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mediaTypeStr = if (isVideo) "VIDEO" else "IMAGE"

        val mimeType = if (mimeIdx != -1 && !cursor.isNull(mimeIdx)) {
            cursor.getString(mimeIdx) ?: if (isVideo) "video/mp4" else "image/jpeg"
        } else {
            if (isVideo) "video/mp4" else "image/jpeg"
        }

        val photoType = getExtFromMimeType(mimeType) ?: if (isVideo) "mp4" else "jpg"
        val size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) runCatching { cursor.getLong(sizeIdx) }.getOrDefault(0L) else 0L
        val dateAdded = if (addedIdx != -1 && !cursor.isNull(addedIdx)) runCatching { cursor.getLong(addedIdx) }.getOrDefault(0L) else 0L
        val dateModified = if (modIdx != -1 && !cursor.isNull(modIdx)) runCatching { cursor.getLong(modIdx) }.getOrDefault(0L) else 0L
        val dateTaken = if (takenIdx != -1 && !cursor.isNull(takenIdx)) runCatching { cursor.getLong(takenIdx) }.getOrDefault(0L) else 0L

        val width = if (widthIdx != -1 && !cursor.isNull(widthIdx)) runCatching { cursor.getInt(widthIdx) }.getOrNull()?.takeIf { it > 0 } else null
        val height = if (heightIdx != -1 && !cursor.isNull(heightIdx)) runCatching { cursor.getInt(heightIdx) }.getOrNull()?.takeIf { it > 0 } else null
        val duration = if (durIdx != -1 && !cursor.isNull(durIdx)) runCatching { cursor.getLong(durIdx) }.getOrNull()?.takeIf { it > 0 } else null

        val bucketId = if (bucketIdIdx != -1 && !cursor.isNull(bucketIdIdx)) runCatching { cursor.getLong(bucketIdIdx) }.getOrDefault(-1L) else -1L
        val bucketName = if (bucketNameIdx != -1 && !cursor.isNull(bucketNameIdx)) cursor.getString(bucketNameIdx) ?: "" else ""

        val baseUri = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val contentUri = ContentUris.withAppendedId(baseUri, id).toString()

        return ScannedMediaItem(
            id = id.toString(),
            contentUri = contentUri,
            mimeType = mimeType,
            photoType = photoType,
            size = size,
            dateAdded = dateAdded,
            dateModified = dateModified,
            dateTaken = dateTaken,
            mediaType = mediaTypeStr,
            width = width,
            height = height,
            duration = duration,
            bucketId = bucketId,
            bucketName = bucketName,
            deviceId = deviceId
        )
    }

    /**
     * Executes a paginated query on MediaStore using modern Bundle args on Android 26+ (API 35 target).
     */
    suspend fun scanBatch(
        context: Context,
        limit: Int,
        offset: Int,
        sinceTimestampSeconds: Long? = null
    ): List<ScannedMediaItem> = withContext(Dispatchers.IO) {
        val accessState = PermissionManager.getMediaAccessState(context)
        if (accessState is MediaAccessState.NoAccess) {
            return@withContext emptyList()
        }

        val (selection, selectionArgs) = buildSelection(accessState, sinceTimestampSeconds)
        if (selection == null || selectionArgs == null) {
            return@withContext emptyList()
        }

        val deviceId = Preferences.getOrCreateDeviceId()
        val resolver = context.contentResolver
        val result = mutableListOf<ScannedMediaItem>()

        try {
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"

            val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    if (limit > 0) {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                    }
                }
                resolver.query(COLLECTION_URI, PROJECTION, bundle, null)
            } else {
                val pagedSortOrder = if (limit > 0) "$sortOrder LIMIT $limit OFFSET $offset" else sortOrder
                resolver.query(COLLECTION_URI, PROJECTION, selection, selectionArgs, pagedSortOrder)
            }

            cursor?.use {
                while (it.moveToNext()) {
                    parseCursorRow(it, deviceId)?.let { item ->
                        result.add(item)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore batch: offset=$offset, limit=$limit", e)
        }

        result
    }

    /**
     * Resumable full gallery indexing in streaming batches of [batchSize].
     * Invokes [onBatch] for each batch retrieved from MediaStore.
     * Returns total items processed.
     */
    suspend fun scanAllInBatches(
        context: Context,
        batchSize: Int = 200,
        sinceTimestampSeconds: Long? = null,
        onBatch: suspend (List<ScannedMediaItem>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var offset = 0
        var totalProcessed = 0

        while (true) {
            val batch = scanBatch(
                context = context,
                limit = batchSize,
                offset = offset,
                sinceTimestampSeconds = sinceTimestampSeconds
            )

            if (batch.isEmpty()) break

            onBatch(batch)
            totalProcessed += batch.size
            offset += batch.size

            if (batch.size < batchSize) break
        }

        Log.i(TAG, "scanAllInBatches completed: totalProcessed=$totalProcessed")
        totalProcessed
    }

    /**
     * Safely checks for deleted local media and removes them from the database.
     * CRITICAL: Inaccessible media is NEVER interpreted as deleted.
     * Pruning is ONLY permitted when [MediaAccessState.FullAccess] is active,
     * and only applies to local-only records (remoteId == null).
     */
    suspend fun pruneDeletedMedia(context: Context, photoDao: PhotoDao) = withContext(Dispatchers.IO) {
        val accessState = PermissionManager.getMediaAccessState(context)
        if (accessState !is MediaAccessState.FullAccess) {
            Log.d(TAG, "Skipping deletion pruning: current access state is $accessState (not FullAccess)")
            return@withContext
        }

        val notUploaded = photoDao.getAllNotUploaded()
        if (notUploaded.isEmpty()) return@withContext

        val contentResolver = context.contentResolver
        var prunedCount = 0

        // High-performance batch check: query all existing IDs in a single indexed projection query
        val (selection, selectionArgs) = buildSelection(accessState)
        val existingIds = HashSet<String>(notUploaded.size)
        try {
            contentResolver.query(
                COLLECTION_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    existingIds.add(cursor.getLong(idIdx).toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query existing MediaStore IDs for pruning", e)
            return@withContext
        }

        for (photo in notUploaded) {
            if (photo.localId !in existingIds) {
                Log.d(TAG, "Pruning deleted local file: id=${photo.localId}, uri=${photo.pathUri}")
                photoDao.deleteById(photo.localId)
                prunedCount++
            }
        }

        if (prunedCount > 0) {
            Log.i(TAG, "Pruned $prunedCount deleted local records from Room")
        }
    }

    /**
     * Formats video duration in milliseconds to "mm:ss" or "h:mm:ss".
     */
    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return ""
        val totalSeconds = (durationMs + 500L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}

/**
 * Clean data model representing a media item discovered from MediaStore.
 */
data class ScannedMediaItem(
    val id: String,
    val contentUri: String,
    val mimeType: String,
    val photoType: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val dateTaken: Long,
    val mediaType: String,
    val width: Int?,
    val height: Int?,
    val duration: Long?,
    val bucketId: Long,
    val bucketName: String,
    val deviceId: String?
) {
    val isVideo: Boolean get() = mediaType == "VIDEO" || mimeType.startsWith("video/")

    fun toPhoto(defaultUploadStatus: String = Photo.STATUS_NONE): Photo = Photo(
        localId = id,
        remoteId = null,
        photoType = photoType,
        pathUri = contentUri,
        contentHash = null,
        uploadStatus = defaultUploadStatus,
        deviceId = deviceId,
        lastUploadAttempt = null,
        uploadRetryCount = 0,
        previewRemoteId = null,
        mediaType = mediaType,
        width = width,
        height = height,
        duration = duration
    )

    fun toLocalUiPhoto(): LocalUiPhoto {
        val ts = when {
            dateTaken > 0L -> dateTaken
            dateModified > 0L -> dateModified * 1000L
            dateAdded > 0L -> dateAdded * 1000L
            else -> 0L
        }
        return LocalUiPhoto(
            localId = id,
            pathUri = contentUri,
            mimeType = mimeType,
            displayDateMillis = ts,
            size = size,
            remoteId = null,
            bucketId = bucketId,
            bucketName = bucketName,
            mediaType = mediaType,
            duration = duration
        )
    }
}
