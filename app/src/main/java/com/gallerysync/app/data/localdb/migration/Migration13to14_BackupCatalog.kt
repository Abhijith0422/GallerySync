package com.gallerysync.app.data.localdb.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 13 -> 14: Introduces backup_catalog and pending_catalog_entries tables
 * for Phase 10.4 Telegram master index and recovery catalog.
 */
class Migration13to14_BackupCatalog : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `backup_catalog` (
                `channelId` INTEGER NOT NULL,
                `botId` INTEGER NOT NULL,
                `lastConfirmedGeneration` INTEGER NOT NULL,
                `pinnedMessageId` INTEGER,
                `indexFileId` TEXT,
                `indexSha256` TEXT,
                `updatedAt` INTEGER NOT NULL,
                `syncStatus` TEXT NOT NULL,
                PRIMARY KEY(`channelId`)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_catalog_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `entryType` TEXT NOT NULL,
                `referenceId` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_catalog_entries_referenceId` 
            ON `pending_catalog_entries` (`referenceId`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_pending_catalog_entries_status` 
            ON `pending_catalog_entries` (`status`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_pending_catalog_entries_createdAt` 
            ON `pending_catalog_entries` (`createdAt`)
            """.trimIndent()
        )
    }
}
