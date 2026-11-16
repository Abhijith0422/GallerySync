package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gallerysync.app.data.restoration.RestorationStatus

@Entity(
    tableName = "video_restoration_jobs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["backupId"]),
        Index(value = ["remoteId"]),
        Index(value = ["createdAt"])
    ]
)
data class VideoRestorationJob(
    @PrimaryKey val restorationId: String,
    @ColumnInfo val backupId: String? = null,
    @ColumnInfo val remoteId: String? = null,
    @ColumnInfo val localId: String? = null,
    @ColumnInfo val originalFileName: String,
    @ColumnInfo val mimeType: String,
    @ColumnInfo val expectedFileSize: Long,
    @ColumnInfo val expectedSha256: String,
    @ColumnInfo val totalChunks: Int,
    @ColumnInfo val verifiedChunks: Int = 0,
    @ColumnInfo val downloadedBytes: Long = 0L,
    @ColumnInfo val status: RestorationStatus = RestorationStatus.PENDING,
    @ColumnInfo val destinationUri: String? = null,
    @ColumnInfo val stagingDirectory: String,
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo val completedAt: Long? = null,
    @ColumnInfo val lastError: String? = null
)
