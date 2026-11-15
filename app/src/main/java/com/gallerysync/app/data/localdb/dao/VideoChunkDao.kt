package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus

@Dao
interface VideoChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chunk: VideoChunk): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<VideoChunk>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(chunk: VideoChunk): Long

    @Update
    suspend fun update(chunk: VideoChunk): Int

    @Query("SELECT * FROM video_chunks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VideoChunk?

    @Query("SELECT * FROM video_chunks WHERE backupId = :backupId ORDER BY chunkIndex ASC")
    suspend fun getByBackupId(backupId: String): List<VideoChunk>

    @Query("SELECT * FROM video_chunks WHERE backupId = :backupId AND chunkIndex = :chunkIndex LIMIT 1")
    suspend fun getByBackupIdAndIndex(backupId: String, chunkIndex: Int): VideoChunk?

    @Query("SELECT * FROM video_chunks WHERE telegramFileId = :telegramFileId LIMIT 1")
    suspend fun getByTelegramFileId(telegramFileId: String): VideoChunk?

    @Query("SELECT * FROM video_chunks WHERE backupId = :backupId AND (status = 'PENDING' OR (status = 'RETRY_WAIT' AND (scheduledRetryAt IS NULL OR scheduledRetryAt <= :now))) ORDER BY chunkIndex ASC")
    suspend fun getPendingChunks(backupId: String, now: Long = System.currentTimeMillis()): List<VideoChunk>

    @Query("SELECT * FROM video_chunks WHERE backupId = :backupId AND (status = 'PENDING' OR (status = 'RETRY_WAIT' AND (scheduledRetryAt IS NULL OR scheduledRetryAt <= :now))) ORDER BY chunkIndex ASC LIMIT 1")
    suspend fun getNextPendingChunk(backupId: String, now: Long = System.currentTimeMillis()): VideoChunk?

    @Query("UPDATE video_chunks SET chunkSha256 = :sha256 WHERE backupId = :backupId AND chunkIndex = :chunkIndex")
    suspend fun updateChunkSha256(backupId: String, chunkIndex: Int, sha256: String): Int

    @Query("UPDATE video_chunks SET status = :status, lastError = :lastError WHERE id = :id")
    suspend fun updateStatus(id: Long, status: VideoChunkStatus, lastError: String? = null): Int

    @Query("UPDATE video_chunks SET status = 'UPLOADING', lastError = NULL WHERE id = :id")
    suspend fun markUploading(id: Long): Int

    @Query("UPDATE video_chunks SET status = 'DONE', telegramFileId = :telegramFileId, telegramMessageId = :telegramMessageId, uploadedAt = :uploadedAt, lastError = NULL WHERE id = :id")
    suspend fun markDone(
        id: Long,
        telegramFileId: String,
        telegramMessageId: Long,
        uploadedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE video_chunks SET status = 'RETRY_WAIT', lastError = :error, retryCount = retryCount + 1, scheduledRetryAt = :scheduledRetryAt WHERE id = :id")
    suspend fun markRetryWait(id: Long, error: String?, scheduledRetryAt: Long): Int

    @Query("UPDATE video_chunks SET status = 'FAILED', lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?): Int

    @Query("SELECT COUNT(*) FROM video_chunks WHERE backupId = :backupId AND status = 'DONE'")
    suspend fun countCompletedChunks(backupId: String): Int

    @Query("SELECT COUNT(*) FROM video_chunks WHERE backupId = :backupId AND status = :status")
    suspend fun countChunksByStatus(backupId: String, status: VideoChunkStatus): Int

    @Query("UPDATE video_chunks SET status = 'PENDING' WHERE backupId = :backupId AND status = 'UPLOADING'")
    suspend fun recoverStaleUploading(backupId: String): Int

    @Query("UPDATE video_chunks SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun recoverAllStaleUploading(): Int

    @Query("DELETE FROM video_chunks WHERE backupId = :backupId")
    suspend fun deleteByBackupId(backupId: String): Int
}
