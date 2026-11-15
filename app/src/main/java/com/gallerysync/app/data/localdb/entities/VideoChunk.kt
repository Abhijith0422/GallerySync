package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an individual slice of a chunked video backup.
 *
 * Guarantees:
 * - Foreign key with ON DELETE CASCADE ensures chunks are cleanly deleted if parent backup is removed.
 * - Unique composite index on (backupId, chunkIndex) guarantees slice ordering and uniqueness.
 * - Index on (backupId, status) optimizes pending slice queries.
 * - Index on telegramFileId enables fast Telegram document lookup.
 */
@Entity(
    tableName = "video_chunks",
    foreignKeys = [
        ForeignKey(
            entity = ChunkedBackup::class,
            parentColumns = ["backupId"],
            childColumns = ["backupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["backupId", "chunkIndex"], unique = true),
        Index(value = ["backupId", "status"]),
        Index(value = ["telegramFileId"])
    ]
)
data class VideoChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo val backupId: String,
    @ColumnInfo val chunkIndex: Int,
    @ColumnInfo val startOffset: Long,
    @ColumnInfo val endOffset: Long,
    @ColumnInfo val chunkSize: Long,
    @ColumnInfo val chunkSha256: String? = null,
    @ColumnInfo val telegramFileId: String? = null,
    @ColumnInfo val telegramMessageId: Long? = null,
    @ColumnInfo val status: VideoChunkStatus = VideoChunkStatus.PENDING,
    @ColumnInfo val retryCount: Int = 0,
    @ColumnInfo val scheduledRetryAt: Long? = null,
    @ColumnInfo val uploadedAt: Long? = null,
    @ColumnInfo val lastError: String? = null
)
