package com.gallerysync.app.data.localdb.entities

import androidx.room.TypeConverter

/**
 * Room type converters for strongly typed chunked backup and chunk status enums.
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
}
