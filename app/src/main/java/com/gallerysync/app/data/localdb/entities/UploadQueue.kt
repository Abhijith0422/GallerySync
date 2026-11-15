package com.gallerysync.app.data.localdb.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["localId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["contentHash"])
    ]
)
data class UploadQueue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo val localId: String,
    @ColumnInfo val pathUri: String,
    @ColumnInfo val contentHash: String? = null,
    @ColumnInfo val status: String = "PENDING",      // PENDING|IN_PROGRESS|DONE|COMPLETED|RETRY_WAIT|FAILED|CANCELLED|EXCEEDS_LIMIT|SOURCE_MISSING
    @ColumnInfo val workerType: String,               // periodic|instant|manual
    @ColumnInfo val workManagerId: String? = null,
    @ColumnInfo val deviceId: String = "",
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo val startedAt: Long? = null,
    @ColumnInfo val completedAt: Long? = null,
    @ColumnInfo val errorMessage: String? = null,
    @ColumnInfo val retryCount: Int = 0,
    @ColumnInfo(defaultValue = "IMAGE") val mediaType: String = "IMAGE",      // IMAGE|VIDEO
    @ColumnInfo val fileSize: Long? = null,
    @ColumnInfo val duration: Long? = null,
    @ColumnInfo val width: Int? = null,
    @ColumnInfo val height: Int? = null,
    @ColumnInfo val scheduledRetryAt: Long? = null
)
