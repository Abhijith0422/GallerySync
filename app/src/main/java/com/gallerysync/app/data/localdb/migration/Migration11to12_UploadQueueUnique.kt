package com.gallerysync.app.data.localdb.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 11 → 12: Enforces queue uniqueness and persistent retry scheduling.
 *
 * Reasons for migration:
 * 1. Previously, upload_queue had a non-unique index on localId. This allowed duplicate rows
 *    for the same local media file when rescans, retries, or concurrent workers ran.
 *    Version 12 enforces a UNIQUE index on localId at the SQLite level to guarantee that only
 *    one authoritative queue record exists per media item.
 * 2. Adds `scheduledRetryAt INTEGER DEFAULT NULL` to durably persist exponential backoff
 *    and Telegram HTTP 429 retry_after timestamps across application restarts and process death.
 * 3. Safely deduplicates existing historical rows before creating the UNIQUE index,
 *    ensuring that if any duplicate was completed (DONE), that completed state is retained.
 */
class Migration11to12_UploadQueueUnique : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i("Migration11to12", "Starting migration 11 → 12 (Queue Uniqueness & Retry Scheduling)")

        // 1. Clean up any invalid rows with null or empty localId
        db.execSQL("DELETE FROM upload_queue WHERE localId IS NULL OR TRIM(localId) = ''")

        // 2. Preserve completed status: if any duplicate was completed (DONE or COMPLETED), mark retained rows as DONE
        db.execSQL(
            """
            UPDATE upload_queue 
            SET status = 'DONE' 
            WHERE localId IN (SELECT localId FROM upload_queue WHERE status IN ('DONE', 'COMPLETED'))
            """.trimIndent()
        )

        // 3. Preserve contentHash: backfill null contentHash from any duplicate that had it
        db.execSQL(
            """
            UPDATE upload_queue
            SET contentHash = (
                SELECT u2.contentHash FROM upload_queue u2 
                WHERE u2.localId = upload_queue.localId AND u2.contentHash IS NOT NULL 
                LIMIT 1
            )
            WHERE contentHash IS NULL
            """.trimIndent()
        )

        // 4. Delete older duplicate rows, keeping the highest row ID per localId
        db.execSQL(
            """
            DELETE FROM upload_queue 
            WHERE id NOT IN (
                SELECT MAX(id) FROM upload_queue GROUP BY localId
            )
            """.trimIndent()
        )

        // 5. Add scheduledRetryAt column to persist retry timestamps across process death
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN scheduledRetryAt INTEGER DEFAULT NULL")

        // 6. Drop old non-unique index and create UNIQUE index on localId
        db.execSQL("DROP INDEX IF EXISTS index_upload_queue_localId")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_upload_queue_localId ON upload_queue (localId)")

        Log.i("Migration11to12", "Migration 11 → 12 completed successfully")
    }
}
