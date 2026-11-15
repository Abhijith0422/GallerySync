package com.gallerysync.app.data.localdb.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration10to11_VideoSupport : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "Starting migration 10 → 11 (Video and Extended Media Metadata Support)")

        // ── 1. photos table: add mediaType, width, height, duration ──
        db.execSQL("ALTER TABLE photos ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
        db.execSQL("ALTER TABLE photos ADD COLUMN width INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE photos ADD COLUMN height INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE photos ADD COLUMN duration INTEGER DEFAULT NULL")

        // ── 2. remote_photos table: add mediaType, width, height, duration ──
        db.execSQL("ALTER TABLE remote_photos ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
        db.execSQL("ALTER TABLE remote_photos ADD COLUMN width INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE remote_photos ADD COLUMN height INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE remote_photos ADD COLUMN duration INTEGER DEFAULT NULL")

        // ── 3. upload_queue table: add mediaType, fileSize, duration, width, height ──
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN fileSize INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN duration INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN width INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE upload_queue ADD COLUMN height INTEGER DEFAULT NULL")

        Log.i(TAG, "Migration 10 → 11 completed successfully")
    }

    companion object {
        private const val TAG = "Migration10to11"
    }
}
