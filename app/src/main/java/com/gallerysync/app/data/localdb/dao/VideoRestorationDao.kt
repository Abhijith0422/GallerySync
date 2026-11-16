package com.gallerysync.app.data.localdb.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gallerysync.app.data.localdb.entities.VideoRestorationJob
import com.gallerysync.app.data.restoration.RestorationStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoRestorationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: VideoRestorationJob): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(job: VideoRestorationJob): Long

    @Update
    suspend fun update(job: VideoRestorationJob): Int

    @Query("SELECT * FROM video_restoration_jobs WHERE restorationId = :restorationId LIMIT 1")
    suspend fun getById(restorationId: String): VideoRestorationJob?

    @Query("SELECT * FROM video_restoration_jobs WHERE backupId = :backupId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByBackupId(backupId: String): VideoRestorationJob?

    @Query("SELECT * FROM video_restoration_jobs WHERE remoteId = :remoteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): VideoRestorationJob?

    @Query("SELECT * FROM video_restoration_jobs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY createdAt ASC")
    suspend fun getActiveJobs(): List<VideoRestorationJob>

    @Query("SELECT * FROM video_restoration_jobs ORDER BY createdAt DESC")
    suspend fun getAll(): List<VideoRestorationJob>

    @Query("SELECT * FROM video_restoration_jobs ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<VideoRestorationJob>>

    @Query("SELECT * FROM video_restoration_jobs WHERE restorationId = :restorationId LIMIT 1")
    fun observeById(restorationId: String): Flow<VideoRestorationJob?>

    @Query("""
        UPDATE video_restoration_jobs 
        SET status = :status, verifiedChunks = :verifiedChunks, downloadedBytes = :downloadedBytes, updatedAt = :updatedAt 
        WHERE restorationId = :restorationId
    """)
    suspend fun updateProgress(
        restorationId: String,
        status: RestorationStatus,
        verifiedChunks: Int,
        downloadedBytes: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE video_restoration_jobs 
        SET status = :status, lastError = :lastError, completedAt = :completedAt, updatedAt = :updatedAt 
        WHERE restorationId = :restorationId
    """)
    suspend fun updateStatus(
        restorationId: String,
        status: RestorationStatus,
        lastError: String? = null,
        completedAt: Long? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE video_restoration_jobs SET destinationUri = :destinationUri, updatedAt = :updatedAt WHERE restorationId = :restorationId")
    suspend fun updateDestinationUri(
        restorationId: String,
        destinationUri: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM video_restoration_jobs WHERE restorationId = :restorationId")
    suspend fun deleteById(restorationId: String): Int

    @Query("DELETE FROM video_restoration_jobs WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM video_restoration_jobs WHERE status = 'CANCELLED'")
    suspend fun deleteCancelled(): Int
}
