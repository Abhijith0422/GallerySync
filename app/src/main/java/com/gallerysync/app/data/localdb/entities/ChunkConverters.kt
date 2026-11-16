package com.gallerysync.app.data.localdb.entities

import androidx.room.TypeConverter
import com.gallerysync.app.data.restoration.RestorationStatus

/**
 * Room type converters for strongly typed chunked backup, chunk status, and restoration status enums.
 */
class ChunkConverters {
    @TypeConverter
    fun fromChunkedBackupStatus(status: ChunkedBackupStatus?): String =
        (status ?: ChunkedBackupStatus.PENDING).name

    @TypeConverter
    fun toChunkedBackupStatus(value: String?): ChunkedBackupStatus =
        ChunkedBackupStatus.fromString(value)

    @TypeConverter
    fun fromVideoChunkStatus(status: VideoChunkStatus?): String =
        (status ?: VideoChunkStatus.PENDING).name

    @TypeConverter
    fun toVideoChunkStatus(value: String?): VideoChunkStatus =
        VideoChunkStatus.fromString(value)

    @TypeConverter
    fun fromRestorationStatus(status: RestorationStatus?): String =
        (status ?: RestorationStatus.PENDING).name

    @TypeConverter
    fun toRestorationStatus(value: String?): RestorationStatus =
        try {
            if (value != null) RestorationStatus.valueOf(value) else RestorationStatus.PENDING
        } catch (_: Exception) {
            RestorationStatus.PENDING
        }
}
