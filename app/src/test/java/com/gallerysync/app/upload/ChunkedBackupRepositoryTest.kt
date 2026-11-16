package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.data.upload.ChunkedBackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedBackupRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DbHolder.database = database
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sampleBackup(
        backupId: String = "repo_backup_1",
        localId: String = "repo_local_1",
        fileSize: Long = 51000000L,
        status: ChunkedBackupStatus = ChunkedBackupStatus.PENDING
    ): ChunkedBackup = ChunkedBackup(
        backupId = backupId,
        localId = localId,
        pathUri = "content://media/external/video/media/200",
        fileName = "VID_SAMPLE.mp4",
        fileSize = fileSize,
        mimeType = "video/mp4",
        width = 1920,
        height = 1080,
        duration = 60000L,
        chunkSize = 16777216L,
        totalChunks = ((fileSize + 16777216L - 1) / 16777216L).toInt(),
        completedChunks = 0,
        status = status,
        channelId = -1001234567890L,
        botId = 7123456789L,
        botUsername = "GallerySyncBot"
    )

    @Test
    fun testMathematicalChunkCalculation() {
        // Case 1: 51 MB (51,000,000 bytes) -> 4 chunks
        val chunks51Mb = ChunkedBackupRepository.calculateChunks("b1", 51000000L)
        assertEquals(4, chunks51Mb.size)
        assertEquals(0L, chunks51Mb[0].startOffset)
        assertEquals(16777216L, chunks51Mb[0].chunkSize)
        assertEquals(16777216L, chunks51Mb[1].chunkSize)
        assertEquals(16777216L, chunks51Mb[2].chunkSize)
        assertEquals(668352L, chunks51Mb[3].chunkSize) // 51,000,000 - (3 * 16,777,216)
        assertEquals(51000000L, chunks51Mb.sumOf { it.chunkSize })

        // Case 2: 51 MiB (53,477,376 bytes) -> 4 chunks
        val chunks51Mib = ChunkedBackupRepository.calculateChunks("b2", 53477376L)
        assertEquals(4, chunks51Mib.size)
        assertEquals(16777216L, chunks51Mib[0].chunkSize)
        assertEquals(3145728L, chunks51Mib[3].chunkSize) // Exactly 3 MiB
        assertEquals(53477376L, chunks51Mib.sumOf { it.chunkSize })

        // Case 3: 1 GiB (1,073,741,824 bytes) -> exactly 64 chunks of 16 MiB
        val chunks1Gib = ChunkedBackupRepository.calculateChunks("b3", 1073741824L)
        assertEquals(64, chunks1Gib.size)
        assertTrue(chunks1Gib.all { it.chunkSize == 16777216L })
        assertEquals(1073741824L, chunks1Gib.sumOf { it.chunkSize })
    }

    @Test
    fun testTransactionalBackupAndChunkCreation() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)

        val created = ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)
        assertEquals("repo_backup_1", created.backupId)

        val retrievedBackup = ChunkedBackupRepository.getBackupById("repo_backup_1", database)
        assertNotNull(retrievedBackup)
        assertEquals("repo_local_1", retrievedBackup!!.localId)

        val retrievedChunks = ChunkedBackupRepository.getChunksForBackup("repo_backup_1", database)
        assertEquals(4, retrievedChunks.size)
        assertEquals(listOf(0, 1, 2, 3), retrievedChunks.map { it.chunkIndex })
    }

    @Test
    fun testDuplicateBackupPreventionReturnsExisting() = runBlocking {
        val backup1 = sampleBackup(backupId = "b1", localId = "duplicate_id")
        val chunks1 = ChunkedBackupRepository.calculateChunks("b1", backup1.fileSize)

        val created1 = ChunkedBackupRepository.createBackupWithChunks(backup1, chunks1, database)
        assertEquals("b1", created1.backupId)

        // Attempt to create second backup with different backupId but same localId
        val backup2 = sampleBackup(backupId = "b2", localId = "duplicate_id")
        val chunks2 = ChunkedBackupRepository.calculateChunks("b2", backup2.fileSize)

        val created2 = ChunkedBackupRepository.createBackupWithChunks(backup2, chunks2, database)
        assertEquals("Must return existing b1", "b1", created2.backupId)

        // Total backups must still be 1
        assertEquals(1, ChunkedBackupRepository.getAllBackups(database).size)
    }

    @Test
    fun testAtomicChunkSuccessUpdatesCompletedCountConsistently() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)
        ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)

        val dbChunks = ChunkedBackupRepository.getChunksForBackup(backup.backupId, database)

        // Mark chunk 0 done
        val completedAfter0 = ChunkedBackupRepository.recordChunkSuccess(
            chunkId = dbChunks[0].id,
            backupId = backup.backupId,
            telegramFileId = "tg_file_0",
            telegramMessageId = 100L,
            database = database
        )
        assertEquals(1, completedAfter0)

        var b = ChunkedBackupRepository.getBackupById(backup.backupId, database)
        assertEquals(1, b!!.completedChunks)

        // Mark chunk 1 done
        val completedAfter1 = ChunkedBackupRepository.recordChunkSuccess(
            chunkId = dbChunks[1].id,
            backupId = backup.backupId,
            telegramFileId = "tg_file_1",
            telegramMessageId = 101L,
            database = database
        )
        assertEquals(2, completedAfter1)

        b = ChunkedBackupRepository.getBackupById(backup.backupId, database)
        assertEquals(2, b!!.completedChunks)
    }

    @Test
    fun testCompletingAllChunksDoesNotMarkBackupDone() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)
        ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)

        val dbChunks = ChunkedBackupRepository.getChunksForBackup(backup.backupId, database)

        // Mark all 4 chunks done
        dbChunks.forEachIndexed { index, chunk ->
            ChunkedBackupRepository.recordChunkSuccess(
                chunkId = chunk.id,
                backupId = backup.backupId,
                telegramFileId = "tg_file_$index",
                telegramMessageId = 100L + index,
                database = database
            )
        }

        val b = ChunkedBackupRepository.getBackupById(backup.backupId, database)
        assertNotNull(b)
        assertEquals(4, b!!.completedChunks)
        assertEquals(4, b.totalChunks)
        // Must NOT be marked DONE automatically: manifest verification is required in later phase
        assertEquals(
            "Backup must remain in initial/in-progress status until manifest creation in Phase 10.4",
            ChunkedBackupStatus.PENDING,
            b.status
        )
    }

    @Test
    fun testRecordManifestSuccessTransitionsToManifestUploadedAndMarkDone() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)
        ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)

        // Upload manifest -> MANIFEST_UPLOADED
        val success = ChunkedBackupRepository.recordManifestSuccess(
            backupId = backup.backupId,
            manifestFileId = "manifest_doc_file_id",
            manifestMessageId = 999L,
            database = database
        )
        assertTrue(success)

        val updated = ChunkedBackupRepository.getBackupById(backup.backupId, database)
        assertNotNull(updated)
        assertEquals(ChunkedBackupStatus.MANIFEST_UPLOADED, updated!!.status)
        assertEquals("manifest_doc_file_id", updated.manifestFileId)
        assertEquals(999L, updated.manifestMessageId)

        // Confirm in Master Index -> DONE
        ChunkedBackupRepository.markBackupDone(backup.backupId, completedAt = 1750000000000L, database = database)
        val done = ChunkedBackupRepository.getBackupById(backup.backupId, database)
        assertNotNull(done)
        assertEquals(ChunkedBackupStatus.DONE, done!!.status)
        assertEquals(1750000000000L, done.completedAt)
    }

    @Test
    fun testStaleUploadingRecovery() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)
        ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)

        val dbChunks = ChunkedBackupRepository.getChunksForBackup(backup.backupId, database)
        ChunkedBackupRepository.markChunkUploading(dbChunks[0].id, database)
        ChunkedBackupRepository.markChunkUploading(dbChunks[1].id, database)

        val pendingBefore = ChunkedBackupRepository.getPendingChunks(backup.backupId, database = database)
        assertEquals(2, pendingBefore.size) // Only 2 and 3 are pending

        // Recover stale
        val recovered = ChunkedBackupRepository.recoverStaleUploadingChunks(backup.backupId, database)
        assertEquals(2, recovered)

        val pendingAfter = ChunkedBackupRepository.getPendingChunks(backup.backupId, database = database)
        assertEquals(4, pendingAfter.size) // All 4 now pending
    }

    @Test
    fun testIncompleteBackupsRetrieval() = runBlocking {
        val b1 = sampleBackup(backupId = "b1", localId = "l1", status = ChunkedBackupStatus.PENDING)
        val b2 = sampleBackup(backupId = "b2", localId = "l2", status = ChunkedBackupStatus.UPLOADING)
        val b3 = sampleBackup(backupId = "b3", localId = "l3", status = ChunkedBackupStatus.DONE)
        val b4 = sampleBackup(backupId = "b4", localId = "l4", status = ChunkedBackupStatus.CANCELLED)

        ChunkedBackupRepository.createBackupWithChunks(b1, ChunkedBackupRepository.calculateChunks("b1", 1000L), database)
        ChunkedBackupRepository.createBackupWithChunks(b2, ChunkedBackupRepository.calculateChunks("b2", 1000L), database)
        ChunkedBackupRepository.createBackupWithChunks(b3, ChunkedBackupRepository.calculateChunks("b3", 1000L), database)
        ChunkedBackupRepository.createBackupWithChunks(b4, ChunkedBackupRepository.calculateChunks("b4", 1000L), database)

        val incomplete = ChunkedBackupRepository.getIncompleteBackups(database)
        assertEquals(2, incomplete.size)
        assertEquals(setOf("b1", "b2"), incomplete.map { it.backupId }.toSet())
    }

    @Test
    fun testExplicitDeletionBehaviour() = runBlocking {
        val backup = sampleBackup()
        val chunks = ChunkedBackupRepository.calculateChunks(backup.backupId, backup.fileSize)
        ChunkedBackupRepository.createBackupWithChunks(backup, chunks, database)

        assertEquals(1, ChunkedBackupRepository.getAllBackups(database).size)
        assertEquals(4, ChunkedBackupRepository.getChunksForBackup(backup.backupId, database).size)

        // Delete backup explicitly
        val deleted = ChunkedBackupRepository.deleteBackup(backup.backupId, database)
        assertTrue(deleted)

        // Verify backup and child chunks are deleted
        assertNull(ChunkedBackupRepository.getBackupById(backup.backupId, database))
        assertTrue(ChunkedBackupRepository.getChunksForBackup(backup.backupId, database).isEmpty())
    }
}
