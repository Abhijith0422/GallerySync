package com.gallerysync.app.data.upload

import android.util.Log
import androidx.room.withTransaction
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import kotlinx.coroutines.flow.Flow

/**
 * Authoritative repository for chunked video backups.
 *
 * Guarantees:
 * 1. Atomicity: Backup records and their child chunks are created together in a single transaction.
 * 2. Deduplication: Unique index on localId prevents concurrent duplicate backup creation.
 * 3. Count Consistency: completedChunks in ChunkedBackup is updated transactionally whenever a chunk
 *    transitions to DONE, derived directly from actual DONE child records.
 * 4. Stale Recovery: Interrupted or crashed UPLOADING chunks are safely recovered back to PENDING.
 * 5. Lifecycle Preservation: Does NOT mark backup DONE merely when completedChunks == totalChunks;
 *    completion requires manifest creation and verification in subsequent phases.
 */
object ChunkedBackupRepository {
    private const val TAG = "ChunkedBackupRepo"
    const val DEFAULT_CHUNK_SIZE = 16 * 1024 * 1024L // 16 MiB (16,777,216 bytes)

    /**
     * Calculates exact chunk boundaries for a file.
     * Uses binary 16 MiB chunk sizing by default, ensuring all chunks except
     * the final chunk are exactly chunkSize bytes, and all chunk sizes sum
     * exactly to fileSize.
     */
    fun calculateChunks(
        backupId: String,
        fileSize: Long,
        chunkSize: Long = DEFAULT_CHUNK_SIZE
    ): List<VideoChunk> {
        require(fileSize > 0) { "File size must be positive: $fileSize" }
        require(chunkSize in 1_048_576L..20_000_000L) {
            "Chunk size must be between 1,048,576 and 20,000,000 bytes (Telegram getFile limit), but was $chunkSize"
        }

        val totalChunksLong = 1L + (fileSize - 1L) / chunkSize
        require(totalChunksLong <= Int.MAX_VALUE) { "Chunk count exceeds supported Int range: $totalChunksLong" }
        val totalChunks = totalChunksLong.toInt()
        return (0 until totalChunks).map { index ->
            val startOffset = index * chunkSize
            val endOffset = minOf(startOffset + chunkSize, fileSize)
            val size = endOffset - startOffset
            VideoChunk(
                backupId = backupId,
                chunkIndex = index,
                startOffset = startOffset,
                endOffset = endOffset,
                chunkSize = size,
                status = VideoChunkStatus.PENDING
            )
        }
    }

    /**
     * Atomically creates a parent ChunkedBackup and all child VideoChunk entities in a single transaction.
     * If a backup for this localId already exists:
     * - Returns the existing backup if active or completed.
     *
     * @return The created or existing ChunkedBackup.
     */
    suspend fun createBackupWithChunks(
        backup: ChunkedBackup,
        chunks: List<VideoChunk>,
        database: WhDatabase = DbHolder.database
    ): ChunkedBackup = database.withTransaction {
        val existing = database.chunkedBackupDao().getByLocalId(backup.localId)
        if (existing != null) {
            Log.d(TAG, "Backup for localId=${backup.localId} already exists (backupId=${existing.backupId}, status=${existing.status})")
            return@withTransaction existing
        }

        database.chunkedBackupDao().insert(backup)
        database.videoChunkDao().insertAll(chunks)
        Log.i(TAG, "Created chunked backup ${backup.backupId} with ${chunks.size} chunks for localId=${backup.localId}")
        backup
    }

    suspend fun getBackupById(
        backupId: String,
        database: WhDatabase = DbHolder.database
    ): ChunkedBackup? = database.chunkedBackupDao().getById(backupId)

    suspend fun getBackupByLocalId(
        localId: String,
        database: WhDatabase = DbHolder.database
    ): ChunkedBackup? = database.chunkedBackupDao().getByLocalId(localId)

    suspend fun getIncompleteBackups(
        database: WhDatabase = DbHolder.database
    ): List<ChunkedBackup> = database.chunkedBackupDao().getIncomplete()

    /**
     * Discovers all backups currently in VERIFYING state (all chunks uploaded, awaiting Phase 10.4 manifest generation).
     * These records remain fully queryable even after their corresponding upload_queue entry is marked DONE.
     */
    suspend fun getVerifyingBackups(
        database: WhDatabase = DbHolder.database
    ): List<ChunkedBackup> = database.chunkedBackupDao().getByStatus(ChunkedBackupStatus.VERIFYING)

    suspend fun getBackupsByStatus(
        status: ChunkedBackupStatus,
        database: WhDatabase = DbHolder.database
    ): List<ChunkedBackup> = database.chunkedBackupDao().getByStatus(status)

    suspend fun getAllBackups(
        database: WhDatabase = DbHolder.database
    ): List<ChunkedBackup> = database.chunkedBackupDao().getAll()

    fun getAllBackupsFlow(
        database: WhDatabase = DbHolder.database
    ): Flow<List<ChunkedBackup>> = database.chunkedBackupDao().getAllFlow()

    suspend fun getChunksForBackup(
        backupId: String,
        database: WhDatabase = DbHolder.database
    ): List<VideoChunk> = database.videoChunkDao().getByBackupId(backupId)

    suspend fun getPendingChunks(
        backupId: String,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): List<VideoChunk> = database.videoChunkDao().getPendingChunks(backupId, now)

    suspend fun getNextPendingChunk(
        backupId: String,
        now: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): VideoChunk? = database.videoChunkDao().getNextPendingChunk(backupId, now)

    suspend fun markChunkUploading(
        chunkId: Long,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.videoChunkDao().markUploading(chunkId) > 0

    /**
     * Atomically records a successful chunk upload:
     * 1. Marks chunk as DONE with telegramFileId, telegramMessageId, and timestamp.
     * 2. Recalculates exact completed chunk count directly from database.
     * 3. Updates parent completedChunks to maintain absolute consistency.
     *
     * Note: Does NOT mark parent backup DONE. Manifest creation occurs in later phases.
     *
     * @return The updated completed chunk count.
     */
    suspend fun recordChunkSuccess(
        chunkId: Long,
        backupId: String,
        telegramFileId: String,
        telegramMessageId: Long,
        uploadedAt: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): Int = database.withTransaction {
        val updated = database.videoChunkDao().markDone(
            id = chunkId,
            telegramFileId = telegramFileId,
            telegramMessageId = telegramMessageId,
            uploadedAt = uploadedAt
        )
        if (updated > 0) {
            val completedCount = database.videoChunkDao().countCompletedChunks(backupId)
            database.chunkedBackupDao().updateCompletedChunks(backupId, completedCount)
            completedCount
        } else {
            database.videoChunkDao().countCompletedChunks(backupId)
        }
    }

    /**
     * Atomically records chunk retry with exponential backoff or 429 retry timestamp.
     */
    suspend fun recordChunkRetry(
        chunkId: Long,
        error: String?,
        scheduledRetryAt: Long,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.videoChunkDao().markRetryWait(chunkId, error, scheduledRetryAt) > 0

    /**
     * Atomically records permanent chunk failure.
     */
    suspend fun recordChunkFailure(
        chunkId: Long,
        backupId: String,
        error: String?,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.withTransaction {
        val updated = database.videoChunkDao().markFailed(chunkId, error) > 0
        database.chunkedBackupDao().updateStatus(
            backupId = backupId,
            status = ChunkedBackupStatus.FAILED,
            lastError = "Chunk failed: $error"
        )
        updated
    }

    suspend fun updateBackupStatus(
        backupId: String,
        status: ChunkedBackupStatus,
        lastError: String? = null,
        completedAt: Long? = null,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.chunkedBackupDao().updateStatus(backupId, status, lastError, completedAt) > 0

    suspend fun updateFileSha256(
        backupId: String,
        sha256: String,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.chunkedBackupDao().updateFileSha256(backupId, sha256) > 0

    suspend fun updateChunkSha256(
        backupId: String,
        chunkIndex: Int,
        sha256: String,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.videoChunkDao().updateChunkSha256(backupId, chunkIndex, sha256) > 0

    /**
     * Atomically persists full-file SHA-256 and all individual chunk hashes in SQLite.
     * Transitions backup status from HASHING to PENDING/UPLOADING when hashes are committed.
     */
    suspend fun updateChunkHashes(
        backupId: String,
        fileSha256: String,
        chunkHashes: Map<Int, String>,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.withTransaction {
        database.chunkedBackupDao().updateFileSha256(backupId, fileSha256)
        chunkHashes.forEach { (index, hash) ->
            database.videoChunkDao().updateChunkSha256(backupId, index, hash)
        }
        database.chunkedBackupDao().updateStatus(backupId, ChunkedBackupStatus.UPLOADING)
        true
    }

    /**
     * Called when manifest document is uploaded and pinned in channel (Phase 10.4).
     * Transitions backup to DONE and records manifest identifiers.
     */
    suspend fun recordManifestSuccess(
        backupId: String,
        manifestFileId: String,
        manifestMessageId: Long,
        completedAt: Long = System.currentTimeMillis(),
        database: WhDatabase = DbHolder.database
    ): Boolean = database.chunkedBackupDao().updateManifestInfo(
        backupId = backupId,
        fileId = manifestFileId,
        messageId = manifestMessageId,
        completedAt = completedAt
    ) > 0

    /**
     * Recovers any chunks stuck in UPLOADING state (e.g., across crash or process death)
     * resetting them back to PENDING.
     */
    suspend fun recoverStaleUploadingChunks(
        backupId: String? = null,
        database: WhDatabase = DbHolder.database
    ): Int = if (backupId != null) {
        database.videoChunkDao().recoverStaleUploading(backupId)
    } else {
        database.videoChunkDao().recoverAllStaleUploading()
    }

    /**
     * Explicit deletion of a backup. Due to ON DELETE CASCADE, all associated chunks
     * are deleted automatically by SQLite.
     */
    suspend fun deleteBackup(
        backupId: String,
        database: WhDatabase = DbHolder.database
    ): Boolean = database.chunkedBackupDao().deleteById(backupId) > 0

    suspend fun deleteCompletedBackups(
        database: WhDatabase = DbHolder.database
    ): Int = database.chunkedBackupDao().deleteCompleted()

    suspend fun deleteCancelledBackups(
        database: WhDatabase = DbHolder.database
    ): Int = database.chunkedBackupDao().deleteCancelled()

    /**
     * Retrieves detailed, byte-accurate progress for a chunked backup.
     * Calculates uploadedBytes by summing actual chunk sizes of DONE chunks,
     * ensuring exact accuracy when the final chunk is smaller than 16 MiB.
     */
    suspend fun getBackupProgress(
        backupId: String,
        database: WhDatabase = DbHolder.database
    ): ChunkedBackupProgress? {
        val backup = database.chunkedBackupDao().getById(backupId) ?: return null
        val chunks = database.videoChunkDao().getByBackupId(backupId)
        val doneChunks = chunks.filter { it.status == VideoChunkStatus.DONE }
        val currentActive = chunks.firstOrNull { it.status == VideoChunkStatus.UPLOADING }
            ?: chunks.firstOrNull { it.status == VideoChunkStatus.PENDING || it.status == VideoChunkStatus.RETRY_WAIT }

        val uploadedBytes = doneChunks.sumOf { it.chunkSize }

        return ChunkedBackupProgress(
            backupId = backup.backupId,
            localId = backup.localId,
            totalChunks = backup.totalChunks,
            completedChunks = doneChunks.size,
            currentChunkIndex = currentActive?.chunkIndex,
            currentChunkSize = currentActive?.chunkSize,
            totalOriginalFileSize = backup.fileSize,
            uploadedBytes = uploadedBytes,
            status = backup.status,
            lastError = backup.lastError
        )
    }
}

/**
 * Byte-accurate progress snapshot for a chunked backup job.
 */
data class ChunkedBackupProgress(
    val backupId: String,
    val localId: String,
    val totalChunks: Int,
    val completedChunks: Int,
    val currentChunkIndex: Int?,
    val currentChunkSize: Long?,
    val totalOriginalFileSize: Long,
    val uploadedBytes: Long,
    val status: ChunkedBackupStatus,
    val lastError: String?
)
