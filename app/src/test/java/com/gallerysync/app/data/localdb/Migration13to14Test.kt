package com.gallerysync.app.data.localdb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.entities.BackupCatalogEntity
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.migration.Migration12to13_ChunkedBackup
import com.gallerysync.app.data.localdb.migration.Migration13to14_BackupCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration13to14Test {

    private lateinit var context: Context
    private val dbName = "test_migration_13_to_14.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun testMigration13to14_preservesDataAndCreatesCatalogTables() = runBlocking {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // 1. Create v13 SQLite database
        val v13Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            v13Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `photos` (
                    `localId` TEXT NOT NULL,
                    `remoteId` TEXT,
                    `photoType` TEXT NOT NULL,
                    `pathUri` TEXT NOT NULL,
                    `contentHash` TEXT,
                    `uploadStatus` TEXT NOT NULL,
                    `deviceId` TEXT,
                    `lastUploadAttempt` INTEGER,
                    `uploadRetryCount` INTEGER NOT NULL,
                    `previewRemoteId` TEXT,
                    `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                    `width` INTEGER,
                    `height` INTEGER,
                    `duration` INTEGER,
                    PRIMARY KEY(`localId`)
                )
                """.trimIndent()
            )
            v13Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `remote_photos` (
                    `remoteId` TEXT NOT NULL,
                    `photoType` TEXT NOT NULL,
                    `fileName` TEXT,
                    `fileSize` INTEGER,
                    `uploadedAt` INTEGER NOT NULL,
                    `thumbnailCached` INTEGER NOT NULL,
                    `messageId` INTEGER,
                    `uploadType` TEXT,
                    `localId` TEXT,
                    `contentHash` TEXT,
                    `status` TEXT NOT NULL,
                    `deletedAt` INTEGER,
                    `uploadedByDevice` TEXT,
                    `deletedByDevice` TEXT,
                    `previewRemoteId` TEXT,
                    `topicId` INTEGER,
                    `topicName` TEXT,
                    `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                    `width` INTEGER,
                    `height` INTEGER,
                    `duration` INTEGER,
                    PRIMARY KEY(`remoteId`)
                )
                """.trimIndent()
            )
            v13Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `upload_queue` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `localId` TEXT NOT NULL,
                    `pathUri` TEXT NOT NULL,
                    `contentHash` TEXT,
                    `status` TEXT NOT NULL,
                    `workerType` TEXT NOT NULL,
                    `workManagerId` TEXT,
                    `deviceId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `startedAt` INTEGER,
                    `completedAt` INTEGER,
                    `errorMessage` TEXT,
                    `retryCount` INTEGER NOT NULL,
                    `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                    `fileSize` INTEGER,
                    `duration` INTEGER,
                    `width` INTEGER,
                    `height` INTEGER,
                    `scheduledRetryAt` INTEGER
                )
                """.trimIndent()
            )
            v13Db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_contentHash` ON `upload_queue` (`contentHash`)")
            v13Db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_queue_localId` ON `upload_queue` (`localId`)")
            v13Db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
            v13Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `deleted_photos` (
                    `remoteId` TEXT NOT NULL,
                    `photoType` TEXT NOT NULL,
                    `fileName` TEXT,
                    `fileSize` INTEGER,
                    `uploadedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER NOT NULL,
                    `messageId` INTEGER,
                    PRIMARY KEY(`remoteId`)
                )
                """.trimIndent()
            )
            v13Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_metadata` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
            v13Db.execSQL(
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
            v13Db.execSQL(
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
                    FOREIGN KEY(`backupId`) REFERENCES `chunked_backups`(`backupId`) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            // Insert sample pre-existing data in v13
            v13Db.execSQL("INSERT INTO photos (localId, photoType, pathUri, uploadStatus, uploadRetryCount) VALUES ('p1', 'jpg', 'uri://1', 'DONE', 0)")
            v13Db.execSQL("INSERT INTO remote_photos (remoteId, photoType, uploadedAt, thumbnailCached, status) VALUES ('tg_file_1', 'jpg', 1234567, 0, 'ACTIVE')")
            v13Db.execSQL("INSERT INTO sync_metadata (`key`, `value`, `updatedAt`) VALUES ('initial_baseline_completed', 'true', 123456)")
            v13Db.version = 13
        } finally {
            v13Db.close()
        }

        // 2. Open with Room using Migration13to14_BackupCatalog
        val roomDb = Room.databaseBuilder(context, WhDatabase::class.java, dbName)
            .addMigrations(Migration12to13_ChunkedBackup(), Migration13to14_BackupCatalog())
            .build()

        try {
            val writableDb = roomDb.openHelper.writableDatabase
            assertEquals("Database version must be upgraded to 14", 14, writableDb.version)

            // Verify pre-existing data preserved
            val photo = roomDb.photoDao().getPhotoByLocalId("p1")
            assertNotNull(photo)
            assertEquals("DONE", photo!!.uploadStatus)

            val remote = roomDb.remotePhotoDao().getById("tg_file_1")
            assertNotNull(remote)
            assertEquals("ACTIVE", remote!!.status)

            // Verify new tables work via Room DAOs
            val catalogDao = roomDb.backupCatalogDao()
            val entryDao = roomDb.pendingCatalogEntryDao()

            val catalogEntity = BackupCatalogEntity(
                channelId = -100123456789L,
                botId = 987654321L,
                lastConfirmedGeneration = 1L,
                pinnedMessageId = 555L,
                indexFileId = "idx_file_001",
                indexSha256 = "abc123sha",
                updatedAt = 1000L
            )
            catalogDao.insertOrUpdate(catalogEntity)

            val retrievedCatalog = catalogDao.getByChannelId(-100123456789L)
            assertNotNull(retrievedCatalog)
            assertEquals(1L, retrievedCatalog!!.lastConfirmedGeneration)
            assertEquals(555L, retrievedCatalog.pinnedMessageId)
            assertEquals("idx_file_001", retrievedCatalog.indexFileId)

            val pendingEntry = PendingCatalogEntry(
                entryType = PendingCatalogEntry.TYPE_MEDIA,
                referenceId = "hash_xyz_123",
                payloadJson = """{"media_sha256":"hash_xyz_123"}""",
                createdAt = 2000L
            )
            val insertId = entryDao.insertOrIgnore(pendingEntry)
            assertTrue(insertId > 0)

            val pendingList = entryDao.getPendingEntries()
            assertEquals(1, pendingList.size)
            assertEquals("hash_xyz_123", pendingList.first().referenceId)
        } finally {
            roomDb.close()
        }
    }
}
