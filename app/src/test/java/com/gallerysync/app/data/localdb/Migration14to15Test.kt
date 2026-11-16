package com.gallerysync.app.data.localdb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.entities.VideoRestorationJob
import com.gallerysync.app.data.localdb.migration.Migration13to14_BackupCatalog
import com.gallerysync.app.data.localdb.migration.Migration14to15_VideoRestoration
import com.gallerysync.app.data.restoration.RestorationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration14to15Test {

    private lateinit var context: Context
    private val dbName = "test_migration_14_to_15.db"

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
    fun testMigration14to15_preservesDataAndCreatesVideoRestorationTable() = runBlocking {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // 1. Create v14 SQLite database
        val v14Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            v14Db.execSQL(
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
            v14Db.execSQL(
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
            v14Db.execSQL(
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
            v14Db.execSQL(
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
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_contentHash` ON `upload_queue` (`contentHash`)")
            v14Db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_queue_localId` ON `upload_queue` (`localId`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
            v14Db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_metadata` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
            v14Db.execSQL(
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
            v14Db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chunked_backups_localId` ON `chunked_backups` (`localId`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunked_backups_status` ON `chunked_backups` (`status`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunked_backups_createdAt` ON `chunked_backups` (`createdAt`)")
            v14Db.execSQL(
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
            v14Db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_video_chunks_backupId_chunkIndex` ON `video_chunks` (`backupId`, `chunkIndex`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_chunks_backupId_status` ON `video_chunks` (`backupId`, `status`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_chunks_telegramFileId` ON `video_chunks` (`telegramFileId`)")
            v14Db.execSQL(
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
            v14Db.execSQL(
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
            v14Db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_catalog_entries_referenceId` ON `pending_catalog_entries` (`referenceId`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_catalog_entries_status` ON `pending_catalog_entries` (`status`)")
            v14Db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_catalog_entries_createdAt` ON `pending_catalog_entries` (`createdAt`)")

            // Insert sample pre-existing data in v14
            v14Db.execSQL("INSERT INTO photos (localId, photoType, pathUri, uploadStatus, uploadRetryCount) VALUES ('p1', 'mp4', 'uri://video1', 'DONE', 0)")
            v14Db.execSQL("INSERT INTO remote_photos (remoteId, photoType, uploadedAt, thumbnailCached, status) VALUES ('tg_file_vid_1', 'mp4', 1234567, 0, 'ACTIVE')")
            v14Db.execSQL("INSERT INTO chunked_backups (backupId, localId, pathUri, fileName, fileSize, mimeType, chunkSize, totalChunks, completedChunks, status, channelId, botId, createdAt) VALUES ('b1', 'p1', 'uri://video1', 'test.mp4', 33554432, 'video/mp4', 16777216, 2, 2, 'DONE', -1001234, 9999, 1000)")
            v14Db.execSQL("INSERT INTO backup_catalog (channelId, botId, lastConfirmedGeneration, updatedAt, syncStatus) VALUES (-1001234, 9999, 5, 2000, 'CONFIRMED')")
            v14Db.version = 14
        } finally {
            v14Db.close()
        }

        // 2. Open with Room using Migration14to15_VideoRestoration
        val roomDb = Room.databaseBuilder(context, WhDatabase::class.java, dbName)
            .addMigrations(
                Migration13to14_BackupCatalog(),
                Migration14to15_VideoRestoration()
            )
            .build()

        try {
            val writableDb = roomDb.openHelper.writableDatabase
            assertEquals("Database version must be upgraded to 15", 15, writableDb.version)

            // Verify pre-existing data preserved
            val photo = roomDb.photoDao().getPhotoByLocalId("p1")
            assertNotNull(photo)
            assertEquals("DONE", photo!!.uploadStatus)

            val remote = roomDb.remotePhotoDao().getById("tg_file_vid_1")
            assertNotNull(remote)
            assertEquals("ACTIVE", remote!!.status)

            val chunkedBackup = roomDb.chunkedBackupDao().getById("b1")
            assertNotNull(chunkedBackup)
            assertEquals(33554432L, chunkedBackup!!.fileSize)
            assertEquals(2, chunkedBackup.totalChunks)

            val catalog = roomDb.backupCatalogDao().getByChannelId(-1001234L)
            assertNotNull(catalog)
            assertEquals(5L, catalog!!.lastConfirmedGeneration)

            // Verify new video_restoration_jobs table works via Room DAO
            val restorationDao = roomDb.videoRestorationDao()

            val job = VideoRestorationJob(
                restorationId = "restore_job_001",
                backupId = "b1",
                remoteId = "tg_file_vid_1",
                localId = "p1",
                originalFileName = "test.mp4",
                mimeType = "video/mp4",
                expectedFileSize = 33554432L,
                expectedSha256 = "abcd1234efgh5678",
                totalChunks = 2,
                verifiedChunks = 1,
                downloadedBytes = 16777216L,
                status = RestorationStatus.DOWNLOADING,
                stagingDirectory = "/data/user/0/com.gallerysync.app/files/restoration/jobs/restore_job_001"
            )
            val insertRowId = restorationDao.insert(job)
            assertTrue("Row ID should be positive", insertRowId > 0)

            val retrievedJob = restorationDao.getById("restore_job_001")
            assertNotNull(retrievedJob)
            assertEquals("b1", retrievedJob!!.backupId)
            assertEquals(RestorationStatus.DOWNLOADING, retrievedJob.status)
            assertEquals(1, retrievedJob.verifiedChunks)
            assertEquals(16777216L, retrievedJob.downloadedBytes)

            // Test progress update
            restorationDao.updateProgress("restore_job_001", RestorationStatus.RECONSTRUCTING, 2, 33554432L)
            val updatedJob = restorationDao.getById("restore_job_001")
            assertNotNull(updatedJob)
            assertEquals(RestorationStatus.RECONSTRUCTING, updatedJob!!.status)
            assertEquals(2, updatedJob.verifiedChunks)
            assertEquals(33554432L, updatedJob.downloadedBytes)

            // Test completion update
            val completedTime = System.currentTimeMillis()
            restorationDao.updateStatus("restore_job_001", RestorationStatus.COMPLETED, completedAt = completedTime)
            val completedJob = restorationDao.getById("restore_job_001")
            assertNotNull(completedJob)
            assertEquals(RestorationStatus.COMPLETED, completedJob!!.status)
            assertEquals(completedTime, completedJob.completedAt)

            // Test active jobs query
            val activeJobs = restorationDao.getActiveJobs()
            assertTrue("Completed job should not appear in active jobs", activeJobs.none { it.restorationId == "restore_job_001" })
        } finally {
            roomDb.close()
        }
    }
}
