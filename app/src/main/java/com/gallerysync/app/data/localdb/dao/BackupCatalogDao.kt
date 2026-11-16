package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(catalog: BackupCatalogEntity): Long

    @Update
    suspend fun update(catalog: BackupCatalogEntity): Int

    @Query("SELECT * FROM backup_catalog WHERE channelId = :channelId LIMIT 1")
    suspend fun getByChannelId(channelId: Long): BackupCatalogEntity?

    @Query("SELECT * FROM backup_catalog WHERE channelId = :channelId LIMIT 1")
    fun getByChannelIdFlow(channelId: Long): Flow<BackupCatalogEntity?>

    @Query("UPDATE backup_catalog SET lastConfirmedGeneration = :generation, pinnedMessageId = :pinnedMessageId, indexFileId = :fileId, indexSha256 = :sha256, updatedAt = :updatedAt, syncStatus = 'COMMITTED' WHERE channelId = :channelId")
    suspend fun recordConfirmedGeneration(
        channelId: Long,
        generation: Long,
        pinnedMessageId: Long,
        fileId: String,
        sha256: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE backup_catalog SET syncStatus = :status WHERE channelId = :channelId")
    suspend fun updateSyncStatus(channelId: Long, status: String): Int

    @Query("DELETE FROM backup_catalog WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: Long): Int
}
