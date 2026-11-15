package com.gallerysync.app.data.localdb.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 12 → 13: Introduces chunk-based video backup tables.
 *
 * 1. Creates `chunked_backups` table tracking multi-gigabyte video backup jobs.
 *    - UNIQUE index on `localId` prevents duplicate parent backup records.
 *    - Indices on `status` and `createdAt` optimize queue queries and history ordering.
 * 2. Creates `video_chunks` child table tracking individual 16 MiB slices.
 *    - Foreign key to `chunked_backups(backupId)` with ON DELETE CASCADE.
 *    - UNIQUE composite index on `(backupId, chunkIndex)` guarantees slice ordering and uniqueness.
 *    - Index on `(backupId, status)` optimizes pending slice retrieval.
 *    - Index on `telegramFileId` enables fast chunk lookup.
 * 3. Preserves all existing tables (`photos`, `remote_photos`, `deleted_photos`, `upload_queue`, `sync_metadata`).
 */
class Migration12to13_ChunkedBackup : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Starting migration 12 → 13 (Chunk-Based Video Backup Architecture)")

        // 1. Create chunked_backups table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chunked_backups` (
                `backupId` TEXT NOT NULL,
                `localId` TEXT NOT NULL,
                `pathUri` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `fileSize` INTEGER NOT NULL,
                `mimeType` TEXT NOT NULL,
                `width` INTEGER,
                `height` INTEGER,
                `duration` INTEGER,
                `fileSha256` TEXT,
                `chunkSize` INTEGER NOT NULL,
                `totalChunks` INTEGER NOT NULL,
                `completedChunks` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `channelId` INTEGER NOT NULL,
                `topicId` INTEGER,
                `botId` INTEGER NOT NULL,
                `botUsername` TEXT,
                `manifestFileId` TEXT,
                `manifestMessageId` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`backupId`)
            )
            """.trimIndent()
        )

        // 2. Create indices for chunked_backups
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chunked_backups_localId` ON `chunked_backups` (`localId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunked_backups_status` ON `chunked_backups` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunked_backups_createdAt` ON `chunked_backups` (`createdAt`)")

        // 3. Create video_chunks table with FOREIGN KEY ON DELETE CASCADE
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `video_chunks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `backupId` TEXT NOT NULL,
                `chunkIndex` INTEGER NOT NULL,
                `startOffset` INTEGER NOT NULL,
                `endOffset` INTEGER NOT NULL,
                `chunkSize` INTEGER NOT NULL,
                `chunkSha256` TEXT,
                `telegramFileId` TEXT,
                `telegramMessageId` INTEGER,
                `status` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `scheduledRetryAt` INTEGER,
                `uploadedAt` INTEGER,
                `lastError` TEXT,
                FOREIGN KEY(`backupId`) REFERENCES `chunked_backups`(`backupId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 4. Create indices for video_chunks
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_video_chunks_backupId_chunkIndex` ON `video_chunks` (`backupId`, `chunkIndex`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_chunks_backupId_status` ON `video_chunks` (`backupId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_chunks_telegramFileId` ON `video_chunks` (`telegramFileId`)")

        Log.i(TAG, "Migration 12 → 13 completed successfully")
    }

    companion object {
        private const val TAG = "Migration12to13"
    }
}
