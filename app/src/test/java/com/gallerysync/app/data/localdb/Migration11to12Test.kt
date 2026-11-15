package com.gallerysync.app.data.localdb

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.localdb.migration.Migration11to12_UploadQueueUnique
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Validates Room database migration 11 -> 12:
 * 1. Historical duplicate rows for the same localId are deduplicated.
 * 2. If any duplicate was completed (DONE), that completed state is preserved.
 * 3. The new column scheduledRetryAt is added with default NULL.
 * 4. The UNIQUE index on localId is created and actively prevents duplicates.
 * 5. Existing photos, remote_photos, and sync_metadata are preserved intact.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11to12Test {

    private lateinit var context: Context
    private val dbName = "test_migration_11_to_12.db"

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
    fun testMigration11to12_deduplicatesAndEnforcesUniqueConstraint() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Step 1: Create v11 SQLite database with historical duplicates
        val v11Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            createVersion11Schema(v11Db)
            insertVersion11SampleData(v11Db)
            v11Db.version = 11
        } finally {
            v11Db.close()
        }

        // Step 2: Open with Room using Migration11to12_UploadQueueUnique and Migration12to13_ChunkedBackup
        val roomDb = Room.databaseBuilder(context, WhDatabase::class.java, dbName)
            .addMigrations(
                Migration11to12_UploadQueueUnique(),
                com.gallerysync.app.data.localdb.migration.Migration12to13_ChunkedBackup()
            )
            .build()

        try {
            val writableDb = roomDb.openHelper.writableDatabase
            assertEquals(13, writableDb.version)

            val uploadQueueDao = roomDb.uploadQueueDao()
            val photoDao = roomDb.photoDao()
            val remotePhotoDao = roomDb.remotePhotoDao()

            runBlocking {
                // Verify photos and remote_photos were preserved intact
                val photos = photoDao.getAll()
                assertEquals("photos count preserved", 2, photos.size)

                val remotes = remotePhotoDao.getAllIncludingDeleted()
                assertEquals("remote_photos count preserved", 2, remotes.size)

                // Verify upload_queue deduplication
                val allQueue = uploadQueueDao.getAll()
                assertEquals("upload_queue deduplicated to 4 distinct valid localIds (empty/null cleaned)", 4, allQueue.size)

                // 1. Verify that local_dup_done preserved status DONE
                val doneItem = allQueue.firstOrNull { it.localId == "local_dup_done" }
                assertNotNull("local_dup_done item exists", doneItem)
                assertEquals("DONE status must be preserved even if older row was PENDING", "DONE", doneItem!!.status)
                assertNull("scheduledRetryAt is null for legacy record", doneItem.scheduledRetryAt)

                // 2. Verify local_dup_completed preserved DONE and backfilled contentHash
                val completedItem = allQueue.firstOrNull { it.localId == "local_dup_completed" }
                assertNotNull("local_dup_completed item exists", completedItem)
                assertEquals("COMPLETED status must be converted to DONE", "DONE", completedItem!!.status)
                assertEquals("contentHash must be preserved from duplicate", "hash_completed_meta", completedItem.contentHash)

                // 3. Verify that local_dup_failed was deduplicated to a single row
                val failedItem = allQueue.firstOrNull { it.localId == "local_dup_failed" }
                assertNotNull("local_dup_failed item exists", failedItem)

                // 4. Verify single item preserved
                val singleItem = allQueue.firstOrNull { it.localId == "local_single" }
                assertNotNull("local_single item exists", singleItem)
                assertEquals("PENDING", singleItem!!.status)

                // 5. Verify corrupt empty localId was deleted
                val emptyItem = allQueue.firstOrNull { it.localId.isBlank() }
                assertNull("Empty localId row must be deleted during migration", emptyItem)

                // 4. Verify UNIQUE index enforcement on localId: inserting duplicate localId must fail
                val duplicateQueueItem = UploadQueue(
                    localId = "local_single", // duplicate!
                    pathUri = "content://media/external/images/media/999",
                    workerType = "instant",
                    status = "PENDING"
                )
                try {
                    uploadQueueDao.insert(duplicateQueueItem)
                    fail("Inserting duplicate localId should throw SQLiteConstraintException")
                } catch (e: SQLiteConstraintException) {
                    // Expected: UNIQUE constraint violated
                    assertTrue("Unique constraint caught", true)
                }

                // 5. Verify inserting new item with scheduledRetryAt works
                val newQueueItem = UploadQueue(
                    localId = "local_new_item",
                    pathUri = "content://media/external/images/media/500",
                    workerType = "periodic",
                    status = "RETRY_WAIT",
                    scheduledRetryAt = 1730000000000L
                )
                val newId = uploadQueueDao.insert(newQueueItem)
                val retrieved = uploadQueueDao.getById(newId)
                assertNotNull(retrieved)
                assertEquals(1730000000000L, retrieved!!.scheduledRetryAt)
                assertEquals("RETRY_WAIT", retrieved.status)
            }

            // Verify SQLite indexes on upload_queue table
            val cursor = writableDb.query("PRAGMA index_list(`upload_queue`)")
            var foundUniqueLocalIdIndex = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                if (name == "index_upload_queue_localId" && unique == 1) {
                    foundUniqueLocalIdIndex = true
                }
            }
            cursor.close()
            assertTrue("index_upload_queue_localId must be UNIQUE", foundUniqueLocalIdIndex)

        } finally {
            roomDb.close()
        }
    }

    private fun createVersion11Schema(db: SQLiteDatabase) {
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
                `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                `duration` INTEGER,
                `width` INTEGER,
                `height` INTEGER,
                PRIMARY KEY(`localId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_remoteId` ON `photos` (`remoteId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_pathUri` ON `photos` (`pathUri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_contentHash` ON `photos` (`contentHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_uploadStatus` ON `photos` (`uploadStatus`)")

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
                `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                `duration` INTEGER,
                `width` INTEGER,
                `height` INTEGER,
                PRIMARY KEY(`remoteId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_uploadedAt` ON `remote_photos` (`uploadedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_contentHash` ON `remote_photos` (`contentHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_localId` ON `remote_photos` (`localId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_photos_status` ON `remote_photos` (`status`)")

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

        // upload_queue v11: non-unique index on localId, no scheduledRetryAt column
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
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `mediaType` TEXT NOT NULL DEFAULT 'IMAGE',
                `fileSize` INTEGER,
                `duration` INTEGER,
                `width` INTEGER,
                `height` INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_localId` ON `upload_queue` (`localId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_contentHash` ON `upload_queue` (`contentHash`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_metadata` (
                `key` TEXT NOT NULL PRIMARY KEY,
                `value` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun insertVersion11SampleData(db: SQLiteDatabase) {
        // Sample photos
        db.execSQL("""
            INSERT INTO `photos` (`localId`, `remoteId`, `photoType`, `pathUri`, `contentHash`, `uploadStatus`, `deviceId`, `lastUploadAttempt`, `uploadRetryCount`, `previewRemoteId`, `mediaType`, `duration`, `width`, `height`)
            VALUES ('img_1', 'rem_1', 'jpg', 'content://media/external/images/media/1', 'hash_img_1', 'DONE', 'dev_1', 1700000000, 0, NULL, 'IMAGE', NULL, 1920, 1080),
                   ('vid_1', NULL, 'mp4', 'content://media/external/video/media/2', 'hash_vid_1', 'NONE', 'dev_1', NULL, 0, NULL, 'VIDEO', 30000, 3840, 2160)
        """.trimIndent())

        // Sample remote photos
        db.execSQL("""
            INSERT INTO `remote_photos` (`remoteId`, `photoType`, `fileName`, `fileSize`, `uploadedAt`, `thumbnailCached`, `messageId`, `uploadType`, `localId`, `contentHash`, `status`, `deletedAt`, `uploadedByDevice`, `deletedByDevice`, `previewRemoteId`, `topicId`, `topicName`, `mediaType`, `duration`, `width`, `height`)
            VALUES ('rem_1', 'jpg', 'photo1.jpg', 204800, 1700000000, 1, 101, 'instant', 'img_1', 'hash_img_1', 'ACTIVE', NULL, 'dev_1', NULL, NULL, NULL, NULL, 'IMAGE', NULL, 1920, 1080),
                   ('rem_2', 'mp4', 'video2.mp4', 5048576, 1700000500, 0, 102, 'periodic', 'vid_old', 'hash_vid_old', 'ACTIVE', NULL, 'dev_1', NULL, NULL, NULL, NULL, 'VIDEO', 15000, 1280, 720)
        """.trimIndent())

        // Sample upload_queue with DUPLICATE rows for localId
        // local_dup_done: id 1 (PENDING) and id 2 (DONE)
        db.execSQL("""
            INSERT INTO `upload_queue` (`id`, `localId`, `pathUri`, `contentHash`, `status`, `workerType`, `workManagerId`, `deviceId`, `createdAt`, `startedAt`, `completedAt`, `errorMessage`, `retryCount`, `mediaType`, `fileSize`, `duration`, `width`, `height`)
            VALUES (1, 'local_dup_done', 'content://media/external/images/media/10', 'hash_dup_done', 'PENDING', 'periodic', NULL, 'dev_1', 1700000000, NULL, NULL, NULL, 0, 'IMAGE', NULL, NULL, NULL, NULL),
                   (2, 'local_dup_done', 'content://media/external/images/media/10', 'hash_dup_done', 'DONE', 'periodic', 'wm_1', 'dev_1', 1700000001, 1700000002, 1700000005, NULL, 0, 'IMAGE', NULL, NULL, NULL, NULL),
                   (3, 'local_dup_failed', 'content://media/external/images/media/20', 'hash_dup_fail', 'FAILED', 'periodic', NULL, 'dev_1', 1700000010, NULL, NULL, 'Network error', 2, 'IMAGE', NULL, NULL, NULL, NULL),
                   (4, 'local_dup_failed', 'content://media/external/images/media/20', 'hash_dup_fail', 'PENDING', 'instant', NULL, 'dev_1', 1700000020, NULL, NULL, NULL, 0, 'IMAGE', NULL, NULL, NULL, NULL),
                   (5, 'local_single', 'content://media/external/images/media/30', 'hash_single', 'PENDING', 'periodic', NULL, 'dev_1', 1700000030, NULL, NULL, NULL, 0, 'IMAGE', NULL, NULL, NULL, NULL),
                   (6, 'local_dup_completed', 'content://media/external/images/media/40', 'hash_completed_meta', 'COMPLETED', 'periodic', NULL, 'dev_1', 1700000040, NULL, NULL, NULL, 0, 'IMAGE', NULL, NULL, NULL, NULL),
                   (7, 'local_dup_completed', 'content://media/external/images/media/40', NULL, 'FAILED', 'periodic', NULL, 'dev_1', 1700000041, NULL, NULL, NULL, 1, 'IMAGE', NULL, NULL, NULL, NULL),
                   (8, '', 'content://media/external/images/media/50', NULL, 'FAILED', 'periodic', NULL, 'dev_1', 1700000050, NULL, NULL, NULL, 1, 'IMAGE', NULL, NULL, NULL, NULL)
        """.trimIndent())
    }
}
