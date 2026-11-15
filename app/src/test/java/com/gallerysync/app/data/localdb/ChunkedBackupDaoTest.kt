package com.gallerysync.app.data.localdb

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.dao.ChunkedBackupDao
import com.gallerysync.app.data.localdb.dao.VideoChunkDao
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedBackupDaoTest {

    private lateinit var context: Context
    private lateinit var db: WhDatabase
    private lateinit var backupDao: ChunkedBackupDao
    private lateinit var chunkDao: VideoChunkDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupDao = db.chunkedBackupDao()
        chunkDao = db.videoChunkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleBackup(
        backupId: String = "backup_1",
        localId: String = "local_media_1",
        totalChunks: Int = 4,
        status: ChunkedBackupStatus = ChunkedBackupStatus.PENDING
    ): ChunkedBackup = ChunkedBackup(
        backupId = backupId,
        localId = localId,
        pathUri = "content://media/external/video/media/100",
        fileName = "VID_20261116.mp4",
        fileSize = 51000000L,
        mimeType = "video/mp4",
        width = 1920,
        height = 1080,
        duration = 120000L,
        chunkSize = 16777216L,
        totalChunks = totalChunks,
        completedChunks = 0,
        status = status,
        channelId = -1001234567890L,
        botId = 7123456789L,
        botUsername = "GallerySyncBot"
    )

    private fun sampleChunks(backupId: String, count: Int = 4): List<VideoChunk> {
        val chunkSize = 16777216L
        val fileSize = 51000000L
        return (0 until count).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, fileSize)
            VideoChunk(
                backupId = backupId,
                chunkIndex = i,
                startOffset = start,
                endOffset = end,
                chunkSize = end - start,
                status = VideoChunkStatus.PENDING
            )
        }
    }

    @Test
    fun testInsertAndRetrieveBackup() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)

        val retrieved = backupDao.getById("backup_1")
        assertNotNull(retrieved)
        assertEquals("backup_1", retrieved!!.backupId)
        assertEquals("local_media_1", retrieved.localId)
        assertEquals(51000000L, retrieved.fileSize)
        assertEquals(ChunkedBackupStatus.PENDING, retrieved.status)
        assertEquals(7123456789L, retrieved.botId)
    }

    @Test
    fun testUniqueLocalIdConstraintOnBackup() = runBlocking {
        val backup1 = sampleBackup(backupId = "backup_1", localId = "duplicate_local")
        val backup2 = sampleBackup(backupId = "backup_2", localId = "duplicate_local")

        backupDao.insert(backup1)
        try {
            backupDao.insert(backup2)
            fail("Inserting duplicate localId should throw SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Expected SQLiteConstraintException on unique localId", true)
        }
    }

    @Test
    fun testInsertAndRetrieveChunksInDeterministicOrder() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)

        val chunks = sampleChunks("backup_1", count = 4)
        chunkDao.insertAll(chunks)

        val retrievedChunks = chunkDao.getByBackupId("backup_1")
        assertEquals(4, retrievedChunks.size)
        assertEquals(listOf(0, 1, 2, 3), retrievedChunks.map { it.chunkIndex })
        assertEquals(0L, retrievedChunks[0].startOffset)
        assertEquals(16777216L, retrievedChunks[0].chunkSize)
        assertEquals(668352L, retrievedChunks[3].chunkSize) // Final chunk
    }

    @Test
    fun testUniqueBackupIdAndChunkIndexConstraint() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)

        val chunk1 = VideoChunk(
            backupId = "backup_1",
            chunkIndex = 0,
            startOffset = 0,
            endOffset = 16777216,
            chunkSize = 16777216
        )
        val duplicateChunk = VideoChunk(
            backupId = "backup_1",
            chunkIndex = 0,
            startOffset = 0,
            endOffset = 16777216,
            chunkSize = 16777216
        )

        chunkDao.insert(chunk1)
        try {
            chunkDao.insert(duplicateChunk)
            fail("Inserting duplicate (backupId, chunkIndex) should throw SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Expected SQLiteConstraintException on composite unique index", true)
        }
    }

    @Test
    fun testForeignKeyEnforcement() = runBlocking {
        // Attempt to insert chunk referencing non-existent backupId
        val orphanChunk = VideoChunk(
            backupId = "non_existent_backup",
            chunkIndex = 0,
            startOffset = 0,
            endOffset = 16777216,
            chunkSize = 16777216
        )

        try {
            chunkDao.insert(orphanChunk)
            fail("Inserting chunk with non-existent backupId should throw SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Foreign key constraint enforced", true)
        }
    }

    @Test
    fun testCascadeDeletion() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)
        chunkDao.insertAll(sampleChunks("backup_1", count = 4))

        assertEquals(4, chunkDao.getByBackupId("backup_1").size)

        // Delete parent backup
        val deleted = backupDao.deleteById("backup_1")
        assertEquals(1, deleted)

        // Verify child chunks are cascaded
        val remainingChunks = chunkDao.getByBackupId("backup_1")
        assertTrue("All child chunks must be deleted via CASCADE", remainingChunks.isEmpty())
    }

    @Test
    fun testStatusTransitionsAndCompletedChunkCountConsistency() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)
        chunkDao.insertAll(sampleChunks("backup_1", count = 4))

        val chunks = chunkDao.getByBackupId("backup_1")
        assertEquals(0, chunkDao.countCompletedChunks("backup_1"))

        // Mark chunk 0 uploading
        chunkDao.markUploading(chunks[0].id)
        val chunk0Uploading = chunkDao.getById(chunks[0].id)
        assertEquals(VideoChunkStatus.UPLOADING, chunk0Uploading!!.status)

        // Mark chunk 0 DONE
        chunkDao.markDone(chunks[0].id, telegramFileId = "doc_file_0", telegramMessageId = 501L)
        val chunk0Done = chunkDao.getById(chunks[0].id)
        assertEquals(VideoChunkStatus.DONE, chunk0Done!!.status)
        assertEquals("doc_file_0", chunk0Done.telegramFileId)
        assertEquals(501L, chunk0Done.telegramMessageId)
        assertNotNull(chunk0Done.uploadedAt)

        // Check count
        assertEquals(1, chunkDao.countCompletedChunks("backup_1"))

        // Mark chunk 1 DONE
        chunkDao.markDone(chunks[1].id, telegramFileId = "doc_file_1", telegramMessageId = 502L)
        assertEquals(2, chunkDao.countCompletedChunks("backup_1"))

        // Update backup completedChunks to match
        backupDao.updateCompletedChunks("backup_1", 2)
        val updatedBackup = backupDao.getById("backup_1")
        assertEquals(2, updatedBackup!!.completedChunks)
    }

    @Test
    fun testRetryTimestampsAndPendingChunkSelection() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)
        chunkDao.insertAll(sampleChunks("backup_1", count = 3))
        val chunks = chunkDao.getByBackupId("backup_1")

        val now = 1000000L
        val futureRetry = now + 60000L

        // Mark chunk 0 RETRY_WAIT in the future
        chunkDao.markRetryWait(chunks[0].id, error = "Rate limit 429", scheduledRetryAt = futureRetry)
        // Chunk 1 remains PENDING
        // Chunk 2 mark DONE
        chunkDao.markDone(chunks[2].id, "file_2", 502L)

        // At time = now, chunk 0 is not yet eligible; only chunk 1 is pending
        val pendingNow = chunkDao.getPendingChunks("backup_1", now = now)
        assertEquals(1, pendingNow.size)
        assertEquals(1, pendingNow[0].chunkIndex)

        // At time = futureRetry + 1, chunk 0 is now eligible along with chunk 1
        val pendingFuture = chunkDao.getPendingChunks("backup_1", now = futureRetry + 1)
        assertEquals(2, pendingFuture.size)
        assertEquals(listOf(0, 1), pendingFuture.map { it.chunkIndex })
    }

    @Test
    fun testStaleUploadingRecovery() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)
        chunkDao.insertAll(sampleChunks("backup_1", count = 3))
        val chunks = chunkDao.getByBackupId("backup_1")

        chunkDao.markUploading(chunks[0].id)
        chunkDao.markUploading(chunks[1].id)
        chunkDao.markDone(chunks[2].id, "file_2", 502L)

        assertEquals(2, chunkDao.countChunksByStatus("backup_1", VideoChunkStatus.UPLOADING))

        // Recover stale
        val recovered = chunkDao.recoverStaleUploading("backup_1")
        assertEquals(2, recovered)

        val pending = chunkDao.getPendingChunks("backup_1")
        assertEquals(2, pending.size)
        assertEquals(listOf(0, 1), pending.map { it.chunkIndex })
        assertEquals(VideoChunkStatus.DONE, chunkDao.getById(chunks[2].id)!!.status)
    }

    @Test
    fun testFlowEmissionOnBackupChanges() = runBlocking {
        val backup = sampleBackup()
        backupDao.insert(backup)

        val flowBackups = backupDao.getAllFlow().first()
        assertEquals(1, flowBackups.size)
        assertEquals("backup_1", flowBackups[0].backupId)
    }
}
