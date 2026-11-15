package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gallerysync.app.data.localdb.entities.UploadQueue
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadQueueDao {
    @Insert
    suspend fun insert(item: UploadQueue): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(item: UploadQueue): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(item: UploadQueue): Long

    @Query("SELECT * FROM upload_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<UploadQueue>

    @Query("SELECT * FROM upload_queue WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): UploadQueue?

    @Query("SELECT * FROM upload_queue WHERE localId = :localId AND status IN ('PENDING','IN_PROGRESS','RETRY_WAIT') LIMIT 1")
    suspend fun getActiveForPhoto(localId: String): UploadQueue?

    @Query("SELECT * FROM upload_queue WHERE contentHash = :hash AND status IN ('PENDING','IN_PROGRESS','DONE','COMPLETED') LIMIT 1")
    suspend fun getByContentHash(hash: String): UploadQueue?

    @Query("SELECT * FROM upload_queue WHERE status = 'PENDING' OR (status = 'RETRY_WAIT' AND (scheduledRetryAt IS NULL OR scheduledRetryAt <= :now)) ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingOrRetryable(now: Long = System.currentTimeMillis(), limit: Int = 50): List<UploadQueue>

    @Query("UPDATE upload_queue SET status = 'IN_PROGRESS', startedAt = :startedAt, workManagerId = :workerId WHERE id = :id AND status IN ('PENDING', 'RETRY_WAIT')")
    suspend fun claimQueueItem(id: Long, workerId: String, startedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE upload_queue SET status = 'IN_PROGRESS', startedAt = :startedAt WHERE id = :id")
    suspend fun markInProgress(id: Long, startedAt: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = 'DONE', completedAt = :completedAt, scheduledRetryAt = NULL, workManagerId = NULL WHERE id = :id")
    suspend fun markDone(id: Long, completedAt: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = 'COMPLETED', completedAt = :completedAt, scheduledRetryAt = NULL, workManagerId = NULL WHERE id = :id")
    suspend fun markCompleted(id: Long, completedAt: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = 'RETRY_WAIT', errorMessage = :error, retryCount = retryCount + 1, scheduledRetryAt = :retryAt, workManagerId = NULL WHERE id = :id")
    suspend fun markRetryWait(id: Long, error: String?, retryAt: Long)

    @Query("UPDATE upload_queue SET status = 'RETRY_WAIT', errorMessage = :error, scheduledRetryAt = :retryAt, workManagerId = NULL WHERE id = :id")
    suspend fun markRateLimited(id: Long, error: String?, retryAt: Long)

    @Query("UPDATE upload_queue SET status = 'FAILED', errorMessage = :error, workManagerId = NULL, scheduledRetryAt = NULL WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?)

    @Query("UPDATE upload_queue SET status = 'CANCELLED', workManagerId = NULL, scheduledRetryAt = NULL WHERE id = :id")
    suspend fun markCancelled(id: Long)

    @Query("UPDATE upload_queue SET status = 'EXCEEDS_LIMIT', errorMessage = :error, workManagerId = NULL, scheduledRetryAt = NULL WHERE id = :id")
    suspend fun markExceedsLimit(id: Long, error: String? = "File exceeds upload size limit")

    @Query("UPDATE upload_queue SET status = 'SOURCE_MISSING', errorMessage = :error, workManagerId = NULL, scheduledRetryAt = NULL WHERE id = :id")
    suspend fun markSourceMissing(id: Long, error: String? = "Source media file missing")

    @Query("SELECT * FROM upload_queue WHERE status = 'IN_PROGRESS' AND startedAt IS NOT NULL AND startedAt < :cutoffTime")
    suspend fun getStaleInProgress(cutoffTime: Long): List<UploadQueue>

    @Query("UPDATE upload_queue SET status = 'PENDING', workManagerId = NULL, scheduledRetryAt = NULL WHERE id = :id AND status = 'IN_PROGRESS'")
    suspend fun resetStaleItem(id: Long): Int

    @Query("UPDATE upload_queue SET status = 'PENDING', workManagerId = NULL, scheduledRetryAt = NULL WHERE workManagerId = :workerId AND status = 'IN_PROGRESS'")
    suspend fun releaseWorkerClaims(workerId: String): Int

    @Query("UPDATE upload_queue SET startedAt = :now WHERE id = :id AND workManagerId = :workerId AND status = 'IN_PROGRESS'")
    suspend fun updateLease(id: Long, workerId: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT MIN(scheduledRetryAt) FROM upload_queue WHERE status = 'RETRY_WAIT' AND scheduledRetryAt IS NOT NULL")
    suspend fun getEarliestScheduledRetry(): Long?

    @Query("UPDATE upload_queue SET status = 'PENDING', errorMessage = NULL, retryCount = 0, scheduledRetryAt = NULL, workManagerId = NULL WHERE id = :id")
    suspend fun resetToPending(id: Long)

    @Query("UPDATE upload_queue SET status = 'PENDING', errorMessage = NULL, retryCount = 0, scheduledRetryAt = NULL, workManagerId = NULL WHERE status IN ('FAILED', 'RETRY_WAIT', 'CANCELLED', 'EXCEEDS_LIMIT', 'SOURCE_MISSING')")
    suspend fun resetAllEligibleToPending(): Int

    @Query("SELECT * FROM upload_queue WHERE id = :id")
    suspend fun getById(id: Long): UploadQueue?

    @Query("DELETE FROM upload_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_queue WHERE status IN ('DONE', 'COMPLETED') AND completedAt < :before")
    suspend fun cleanupOld(before: Long)

    @Query("DELETE FROM upload_queue WHERE status IN ('DONE', 'COMPLETED')")
    suspend fun deleteCompleted(): Int

    @Query("SELECT * FROM upload_queue ORDER BY createdAt DESC")
    suspend fun getAll(): List<UploadQueue>

    @Query("SELECT * FROM upload_queue ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<UploadQueue>>

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('PENDING', 'RETRY_WAIT')")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = 'IN_PROGRESS'")
    fun getActiveCountFlow(): Flow<Int>
}
