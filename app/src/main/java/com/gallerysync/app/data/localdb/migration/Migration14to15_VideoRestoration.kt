package com.gallerysync.app.data.localdb.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 14 -> 15: Introduces video_restoration_jobs table
 * for Phase 10.5 video restoration and chunk reconstruction system.
 */
class Migration14to15_VideoRestoration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `video_restoration_jobs` (
                `restorationId` TEXT NOT NULL,
                `backupId` TEXT,
                `remoteId` TEXT,
                `localId` TEXT,
                `originalFileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `expectedFileSize` INTEGER NOT NULL,
                `expectedSha256` TEXT NOT NULL,
                `totalChunks` INTEGER NOT NULL,
                `verifiedChunks` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `destinationUri` TEXT,
                `stagingDirectory` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`restorationId`)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_video_restoration_jobs_status` 
            ON `video_restoration_jobs` (`status`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_video_restoration_jobs_backupId` 
            ON `video_restoration_jobs` (`backupId`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_video_restoration_jobs_remoteId` 
            ON `video_restoration_jobs` (`remoteId`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_video_restoration_jobs_createdAt` 
            ON `video_restoration_jobs` (`createdAt`)
            """.trimIndent()
        )
    }
}
