package com.gallerysync.app.data.localdb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.migration.Migration10to11_VideoSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Migration10to11Test {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val dbName = "migration_test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @After
    fun tearDown() {
        if (::dbFile.isInitialized && dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun testMigration10to11_preservesDataAndAddsNewColumns() {
        // Step 1: Create version 10 SQLite database manually with exact original schema
        dbFile.parentFile?.mkdirs()
        val v10Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            createVersion10Schema(v10Db)
            insertVersion10SampleData(v10Db)
            v10Db.version = 10
        } finally {
            v10Db.close()
        }

        // Step 2: Open with Room using registered migrations up to current version 15
        val roomDb = Room.databaseBuilder(context, WhDatabase::class.java, dbName)
            .addMigrations(
                com.gallerysync.app.data.localdb.migration.Migration10to11_VideoSupport(),
                com.gallerysync.app.data.localdb.migration.Migration11to12_UploadQueueUnique(),
                com.gallerysync.app.data.localdb.migration.Migration12to13_ChunkedBackup(),
                com.gallerysync.app.data.localdb.migration.Migration13to14_BackupCatalog(),
                com.gallerysync.app.data.localdb.migration.Migration14to15_VideoRestoration()
            )
            .build()

        try {
            // Force database opening, migration execution, and Room schema validation
            val writableDb = roomDb.openHelper.writableDatabase
            assertEquals(15, writableDb.version)

            // Step 3: Verify record counts
            val photoDao = roomDb.photoDao()
            val remotePhotoDao = roomDb.remotePhotoDao()
            val uploadQueueDao = roomDb.uploadQueueDao()

            runBlocking {
                val allPhotos = photoDao.getAll()
                assertEquals("photos count before and after migration must match", 2, allPhotos.size)

                val allRemote = remotePhotoDao.getAllIncludingDeleted()
                assertEquals("remote_photos count before and after migration must match", 2, allRemote.size)

                val pendingQueue = uploadQueueDao.getPending()
                assertEquals("pending upload_queue count must match", 1, pendingQueue.size)

                // Step 4: Verify existing fields remain intact & new fields receive correct defaults in 'photos'
                val photo1 = photoDao.getPhotoByLocalId("local_1")
                assertNotNull(photo1)
                assertEquals("local_1", photo1!!.localId)
                assertEquals("rem_1", photo1.remoteId)
                assertEquals("jpg", photo1.photoType) // Existing photoType must remain unchanged
                assertEquals("content://media/external/images/media/1", photo1.pathUri)
                assertEquals("hash_abc123", photo1.contentHash)
                assertEquals("DONE", photo1.uploadStatus)
                assertEquals("dev_1", photo1.deviceId)
                assertEquals(1700000000000L, photo1.lastUploadAttempt)
                assertEquals(0, photo1.uploadRetryCount)
                assertEquals("prev_1", photo1.previewRemoteId)
                // New v11 fields on legacy record
                assertEquals("IMAGE", photo1.mediaType)
                assertNull(photo1.width)
                assertNull(photo1.height)
                assertNull(photo1.duration)

                val photo2 = photoDao.getPhotoByLocalId("local_2")
                assertNotNull(photo2)
                assertEquals("local_2", photo2!!.localId)
                assertNull(photo2.remoteId)
                assertEquals("png", photo2.photoType)
                assertEquals("IMAGE", photo2.mediaType)
                assertNull(photo2.width)
                assertNull(photo2.height)
                assertNull(photo2.duration)
                assertEquals(1, photo2.uploadRetryCount)

                // Step 5: Verify existing fields & new fields in 'remote_photos'
                val remote1 = remotePhotoDao.getById("rem_1")
                assertNotNull(remote1)
                assertEquals("rem_1", remote1!!.remoteId)
                assertEquals("jpg", remote1.photoType)
                assertEquals("IMG_2026.jpg", remote1.fileName)
                assertEquals(2048576L, remote1.fileSize)
                assertEquals(1700000000000L, remote1.uploadedAt)
                assertTrue(remote1.thumbnailCached)
                assertEquals(1001L, remote1.messageId)
                assertEquals("instant", remote1.uploadType)
                assertEquals("local_1", remote1.localId)
                assertEquals("hash_abc123", remote1.contentHash)
                assertEquals("ACTIVE", remote1.status)
                assertNull(remote1.deletedAt)
                assertEquals("dev_1", remote1.uploadedByDevice)
                assertEquals("prev_1", remote1.previewRemoteId)
                assertEquals(42L, remote1.topicId)
                assertEquals("Vacation", remote1.topicName)
                // New v11 fields on legacy record
                assertEquals("IMAGE", remote1.mediaType)
                assertNull(remote1.width)
                assertNull(remote1.height)
                assertNull(remote1.duration)

                val remote2 = allRemote.find { it.remoteId == "rem_2" }
                assertNotNull(remote2)
                assertEquals("rem_2", remote2!!.remoteId)
                assertEquals("heic", remote2.photoType)
                assertEquals("DELETED", remote2.status)
                assertEquals(1700002000000L, remote2.deletedAt)
                assertEquals("dev_2", remote2.uploadedByDevice)
                assertEquals("dev_1", remote2.deletedByDevice)
                assertEquals("IMAGE", remote2.mediaType)
                assertNull(remote2.width)
                assertNull(remote2.height)
                assertNull(remote2.duration)

                // Step 6: Verify upload_queue records
                val queue1 = uploadQueueDao.getById(1L)
                assertNotNull(queue1)
                assertEquals(1L, queue1!!.id)
                assertEquals("local_2", queue1.localId)
                assertEquals("PENDING", queue1.status)
                assertEquals("instant", queue1.workerType)
                assertEquals("wm-uuid-1", queue1.workManagerId)
                assertEquals("dev_1", queue1.deviceId)
                // New v11 fields on legacy record
                assertEquals("IMAGE", queue1.mediaType)
                assertNull(queue1.fileSize)
                assertNull(queue1.duration)
                assertNull(queue1.width)
                assertNull(queue1.height)

                val queue2 = uploadQueueDao.getById(2L)
                assertNotNull(queue2)
                assertEquals(2L, queue2!!.id)
                assertEquals("local_3", queue2.localId)
                assertEquals("FAILED", queue2.status)
                assertEquals("Network timeout", queue2.errorMessage)
                assertEquals(2, queue2.retryCount)
                assertEquals("IMAGE", queue2.mediaType)
                assertNull(queue2.fileSize)
                assertNull(queue2.duration)

                // Step 7: Verify new video insert into migrated v11 database
                val videoPhoto = Photo(
                    localId = "video_local_1",
                    remoteId = null,
                    photoType = "mp4",
                    pathUri = "content://media/external/video/media/100",
                    contentHash = "hash_video_999",
                    uploadStatus = "PENDING",
                    deviceId = "dev_1",
                    mediaType = "VIDEO",
                    width = 1920,
                    height = 1080,
                    duration = 45000L
                )
                photoDao.insertPhotos(videoPhoto)

                val retrievedVideo = photoDao.getPhotoByLocalId("video_local_1")
                assertNotNull(retrievedVideo)
                assertEquals("VIDEO", retrievedVideo!!.mediaType)
                assertEquals(1920, retrievedVideo.width)
                assertEquals(1080, retrievedVideo.height)
                assertEquals(45000L, retrievedVideo.duration)
                assertEquals("mp4", retrievedVideo.photoType)
            }

            // Step 8: Verify table indices in SQLite directly
            verifyIndices(writableDb)

        } finally {
            roomDb.close()
        }
    }

    private fun createVersion10Schema(db: SQLiteDatabase) {
        // photos table (v10)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `photos` (
                `localId` TEXT NOT NULL,
                `remoteId` TEXT,
                `photoType` TEXT NOT NULL,
                `pathUri` TEXT NOT NULL,
                `contentHash` TEXT,
                `uploadStatus` TEXT NOT NULL DEFAULT 'NONE',
                `deviceId` TEXT,
                `lastUploadAttempt` INTEGER,
                `uploadRetryCount` INTEGER NOT NULL DEFAULT 0,
                `previewRemoteId` TEXT,
                PRIMARY KEY(`localId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_remoteId` ON `photos` (`remoteId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_pathUri` ON `photos` (`pathUri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_contentHash` ON `photos` (`contentHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_uploadStatus` ON `photos` (`uploadStatus`)")

        // remote_photos table (v10)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `remote_photos` (
                `remoteId` TEXT NOT NULL,
                `photoType` TEXT NOT NULL,
                `fileName` TEXT,
                `fileSize` INTEGER,
                `uploadedAt` INTEGER NOT NULL,
                `thumbnailCached` INTEGER NOT NULL DEFAULT 0,
                `messageId` INTEGER,
                `uploadType` TEXT,
                `localId` TEXT,
                `contentHash` TEXT,
                `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                `deletedAt` INTEGER,
                `uploadedByDevice` TEXT,
                `deletedByDevice` TEXT,
                `previewRemoteId` TEXT,
                `topicId` INTEGER,
                `topicName` TEXT,
                PRIMARY KEY(`remoteId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_uploadedAt` ON `remote_photos` (`uploadedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_contentHash` ON `remote_photos` (`contentHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_localId` ON `remote_photos` (`localId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_status` ON `remote_photos` (`status`)")

        // deleted_photos table (v10)
        db.execSQL("""
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
        """.trimIndent())

        // upload_queue table (v10)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `upload_queue` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `localId` TEXT NOT NULL,
                `pathUri` TEXT NOT NULL,
                `contentHash` TEXT,
                `status` TEXT NOT NULL DEFAULT 'PENDING',
                `workerType` TEXT NOT NULL,
                `workManagerId` TEXT,
                `deviceId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `startedAt` INTEGER,
                `completedAt` INTEGER,
                `errorMessage` TEXT,
                `retryCount` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_localId` ON `upload_queue` (`localId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_contentHash` ON `upload_queue` (`contentHash`)")

        // sync_metadata table (v10)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_metadata` (
                `key` TEXT NOT NULL PRIMARY KEY,
                `value` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun insertVersion10SampleData(db: SQLiteDatabase) {
        db.execSQL("""
            INSERT INTO `photos` (localId, remoteId, photoType, pathUri, contentHash, uploadStatus, deviceId, lastUploadAttempt, uploadRetryCount, previewRemoteId)
            VALUES ('local_1', 'rem_1', 'jpg', 'content://media/external/images/media/1', 'hash_abc123', 'DONE', 'dev_1', 1700000000000, 0, 'prev_1')
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `photos` (localId, remoteId, photoType, pathUri, contentHash, uploadStatus, deviceId, lastUploadAttempt, uploadRetryCount, previewRemoteId)
            VALUES ('local_2', NULL, 'png', 'content://media/external/images/media/2', 'hash_def456', 'PENDING', 'dev_1', NULL, 1, NULL)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `remote_photos` (remoteId, photoType, fileName, fileSize, uploadedAt, thumbnailCached, messageId, uploadType, localId, contentHash, status, deletedAt, uploadedByDevice, deletedByDevice, previewRemoteId, topicId, topicName)
            VALUES ('rem_1', 'jpg', 'IMG_2026.jpg', 2048576, 1700000000000, 1, 1001, 'instant', 'local_1', 'hash_abc123', 'ACTIVE', NULL, 'dev_1', NULL, 'prev_1', 42, 'Vacation')
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `remote_photos` (remoteId, photoType, fileName, fileSize, uploadedAt, thumbnailCached, messageId, uploadType, localId, contentHash, status, deletedAt, uploadedByDevice, deletedByDevice, previewRemoteId, topicId, topicName)
            VALUES ('rem_2', 'heic', 'IMG_2027.heic', 1048576, 1700001000000, 0, 1002, 'background', NULL, 'hash_ghi789', 'DELETED', 1700002000000, 'dev_2', 'dev_1', NULL, NULL, NULL)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `upload_queue` (id, localId, pathUri, contentHash, status, workerType, workManagerId, deviceId, createdAt, startedAt, completedAt, errorMessage, retryCount)
            VALUES (1, 'local_2', 'content://media/external/images/media/2', 'hash_def456', 'PENDING', 'instant', 'wm-uuid-1', 'dev_1', 1700000500000, NULL, NULL, NULL, 0)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `upload_queue` (id, localId, pathUri, contentHash, status, workerType, workManagerId, deviceId, createdAt, startedAt, completedAt, errorMessage, retryCount)
            VALUES (2, 'local_3', 'content://media/external/images/media/3', 'hash_jkl012', 'FAILED', 'periodic', 'wm-uuid-2', 'dev_1', 1700000600000, 1700000700000, NULL, 'Network timeout', 2)
        """.trimIndent())
    }

    private fun verifyIndices(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='index'")
        val indexNames = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indexNames.add(cursor.getString(0))
        }
        cursor.close()

        val expectedIndices = listOf(
            "index_photos_remoteId",
            "index_photos_pathUri",
            "index_photos_contentHash",
            "index_photos_uploadStatus",
            "index_remote_photos_uploadedAt",
            "index_remote_photos_contentHash",
            "index_remote_photos_localId",
            "index_remote_photos_status",
            "index_upload_queue_localId",
            "index_upload_queue_status",
            "index_upload_queue_contentHash"
        )
        for (index in expectedIndices) {
            assertTrue("Expected index '$index' to exist after migration", indexNames.contains(index))
        }
    }
}
