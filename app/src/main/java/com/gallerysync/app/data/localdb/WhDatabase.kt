package com.gallerysync.app.data.localdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gallerysync.app.data.localdb.dao.DeletedPhotoDao
import com.gallerysync.app.data.localdb.dao.PhotoDao
import com.gallerysync.app.data.localdb.dao.RemotePhotoDao
import com.gallerysync.app.data.localdb.dao.UploadQueueDao
import com.gallerysync.app.data.localdb.dao.SyncMetadataDao
import com.gallerysync.app.data.localdb.dao.ChunkedBackupDao
import com.gallerysync.app.data.localdb.dao.VideoChunkDao
import com.gallerysync.app.data.localdb.entities.DeletedPhoto
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.localdb.entities.SyncMetadata
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.ChunkConverters
import com.gallerysync.app.data.localdb.migration.Migration1to2_NullableRemoteId
import com.gallerysync.app.data.localdb.migration.Migration2to3_RemotePhotoTable
import com.gallerysync.app.data.localdb.migration.Migration3to4_EnhancedRemotePhoto
import com.gallerysync.app.data.localdb.migration.Migration4to5_DeletedPhotos
import com.gallerysync.app.data.localdb.migration.Migration7to8_MultiDevice
import com.gallerysync.app.data.localdb.migration.Migration8to9_PreviewRemoteId
import com.gallerysync.app.data.localdb.migration.Migration9to10_ForumTopics
import com.gallerysync.app.data.localdb.dao.BackupCatalogDao
import com.gallerysync.app.data.localdb.dao.PendingCatalogEntryDao
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.migration.Migration10to11_VideoSupport
import com.gallerysync.app.data.localdb.migration.Migration11to12_UploadQueueUnique
import com.gallerysync.app.data.localdb.migration.Migration12to13_ChunkedBackup
import com.gallerysync.app.data.localdb.migration.Migration13to14_BackupCatalog

@Database(
    entities = [
        Photo::class,
        RemotePhoto::class,
        DeletedPhoto::class,
        UploadQueue::class,
        SyncMetadata::class,
        ChunkedBackup::class,
        VideoChunk::class,
        BackupCatalogEntity::class,
        PendingCatalogEntry::class
    ],
    version = 14,
    exportSchema = false
)
@TypeConverters(ChunkConverters::class)
abstract class WhDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun remotePhotoDao(): RemotePhotoDao
    @Deprecated("Use RemotePhotoDao soft-delete instead. Kept for backward compatibility.")
    abstract fun deletedPhotoDao(): DeletedPhotoDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun chunkedBackupDao(): ChunkedBackupDao
    abstract fun videoChunkDao(): VideoChunkDao
    abstract fun backupCatalogDao(): BackupCatalogDao
    abstract fun pendingCatalogEntryDao(): PendingCatalogEntryDao

    companion object {
        private const val DATABASE_NAME = "wh_database"

        @Volatile
        private var INSTANCE: WhDatabase? = null

        fun getInstance(context: Context): WhDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WhDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        Migration1to2_NullableRemoteId(),
                        Migration2to3_RemotePhotoTable(),
                        Migration3to4_EnhancedRemotePhoto(),
                        Migration4to5_DeletedPhotos(),
                        com.gallerysync.app.data.localdb.migration.Migration5to6_MessageId(),
                        com.gallerysync.app.data.localdb.migration.Migration6to7_UploadType(),
                        Migration7to8_MultiDevice(),
                        Migration8to9_PreviewRemoteId(),
                        Migration9to10_ForumTopics(),
                        Migration10to11_VideoSupport(),
                        Migration11to12_UploadQueueUnique(),
                        Migration12to13_ChunkedBackup(),
                        Migration13to14_BackupCatalog()
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
