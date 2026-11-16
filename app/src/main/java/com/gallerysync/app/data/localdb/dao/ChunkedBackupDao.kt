package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkedBackupDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(backup: ChunkedBackup): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(backup: ChunkedBackup): Long

    @Update
    suspend fun update(backup: ChunkedBackup): Int

    @Query("SELECT * FROM chunked_backups WHERE backupId = :backupId LIMIT 1")
    suspend fun getById(backupId: String): ChunkedBackup?

    @Query("SELECT * FROM chunked_backups WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): ChunkedBackup?

    @Query("SELECT * FROM chunked_backups ORDER BY createdAt DESC")
    suspend fun getAll(): List<ChunkedBackup>

    @Query("SELECT * FROM chunked_backups ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ChunkedBackup>>

    @Query("SELECT * FROM chunked_backups WHERE status NOT IN ('DONE', 'CANCELLED') ORDER BY createdAt ASC")
    suspend fun getIncomplete(): List<ChunkedBackup>

    @Query("SELECT * FROM chunked_backups WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: ChunkedBackupStatus): List<ChunkedBackup>

    @Query("UPDATE chunked_backups SET status = :status, lastError = :lastError, completedAt = :completedAt WHERE backupId = :backupId")
    suspend fun updateStatus(
        backupId: String,
        status: ChunkedBackupStatus,
        lastError: String? = null,
        completedAt: Long? = null
    ): Int

    @Query("UPDATE chunked_backups SET completedChunks = :completedChunks WHERE backupId = :backupId")
    suspend fun updateCompletedChunks(backupId: String, completedChunks: Int): Int

    @Query("UPDATE chunked_backups SET fileSha256 = :sha256 WHERE backupId = :backupId")
    suspend fun updateFileSha256(backupId: String, sha256: String): Int

    @Query("UPDATE chunked_backups SET manifestFileId = :fileId, manifestMessageId = :messageId, status = 'MANIFEST_UPLOADED', lastError = NULL WHERE backupId = :backupId")
    suspend fun updateManifestInfo(
        backupId: String,
        fileId: String,
        messageId: Long
    ): Int

    @Query("UPDATE chunked_backups SET status = 'DONE', completedAt = :completedAt, lastError = NULL WHERE backupId = :backupId")
    suspend fun markBackupDone(
        backupId: String,
        completedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM chunked_backups WHERE backupId = :backupId")
    suspend fun deleteById(backupId: String): Int

    @Query("DELETE FROM chunked_backups WHERE status = 'DONE'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM chunked_backups WHERE status = 'CANCELLED'")
    suspend fun deleteCancelled(): Int
}
