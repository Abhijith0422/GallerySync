package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a high-level chunked video backup job.
 *
 * Guarantees:
 * - Unique localId ensures at most one active chunked backup exists per local media item.
 * - Tracks overall progress, status, Telegram channel metadata, and bot identity.
 * - Completed status is decoupled from chunk completion; completion requires manifest creation in Phase 10.4.
 */
@Entity(
    tableName = "chunked_backups",
    indices = [
        Index(value = ["localId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class ChunkedBackup(
    @PrimaryKey val backupId: String,
    @ColumnInfo val localId: String,
    @ColumnInfo val pathUri: String,
    @ColumnInfo val fileName: String,
    @ColumnInfo val fileSize: Long,
    @ColumnInfo val mimeType: String,
    @ColumnInfo val width: Int? = null,
    @ColumnInfo val height: Int? = null,
    @ColumnInfo val duration: Long? = null,
    @ColumnInfo val fileSha256: String? = null,
    @ColumnInfo val chunkSize: Long = 16777216L, // 16 MiB default
    @ColumnInfo val totalChunks: Int,
    @ColumnInfo val completedChunks: Int = 0,
    @ColumnInfo val status: ChunkedBackupStatus = ChunkedBackupStatus.PENDING,
    @ColumnInfo val channelId: Long,
    @ColumnInfo val topicId: Long? = null,
    @ColumnInfo val botId: Long,
    @ColumnInfo val botUsername: String? = null,
    @ColumnInfo val manifestFileId: String? = null,
    @ColumnInfo val manifestMessageId: Long? = null,
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo val completedAt: Long? = null,
    @ColumnInfo val lastError: String? = null
)
