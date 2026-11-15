package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.api.TelegramChunkApi
import com.gallerysync.app.api.TelegramChunkUploadResult
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.data.upload.ChunkedBackupRepository
import com.gallerysync.app.data.upload.ChunkedUploadEngine
import com.gallerysync.app.data.upload.ChunkedUploadResult
import com.gallerysync.app.data.upload.SyncNetworkPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedUploadEngineTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private val testChannelId = -1001234567890L
    private val testBotToken = "99887766:ABCdefGHI_token"
    private val testBotId = 99887766L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
        Preferences.editEncrypted {
            putString(Preferences.botToken, testBotToken)
            putLong(Preferences.channelId, testChannelId)
        }

        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DbHolder.database = database

        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.Eligible
    }

    @After
    fun tearDown() {
        SyncNetworkPolicy.networkStatusOverride = null
        database.close()
    }

    private fun createTestMediaFile(name: String, sizeBytes: Int): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(minOf(64 * 1024, sizeBytes))
            var written = 0
            while (written < sizeBytes) {
                val toWrite = minOf(buf.size, sizeBytes - written)
                fos.write(buf, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    private fun createMockChunkApi(
        onChunkUpload: (fileName: String, expectedSize: Long) -> TelegramChunkUploadResult
    ): TelegramChunkApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        return object : TelegramChunkApi(client) {
            override suspend fun sendChunkDocument(
                botToken: String,
                channelId: Long,
                chunkRequestBody: RequestBody,
                fileName: String,
                expectedSize: Long,
                messageThreadId: Long?,
                caption: String?,
                baseUrl: String
            ): TelegramChunkUploadResult {
                return onChunkUpload(fileName, expectedSize)
            }
        }
    }

    @Test
    fun testParentJobCreatedAndDelegated() = runBlocking {
        val testFile = createTestMediaFile("video1.mp4", 100 * 1024)
        val photo = Photo(
            localId = "local_video_1",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        var uploadCallCount = 0
        val mockApi = createMockChunkApi { fileName, size ->
            uploadCallCount++
            TelegramChunkUploadResult.Success(
                fileId = "remote_file_$uploadCallCount",
                messageId = 1000L + uploadCallCount,
                fileSize = size,
                chatId = testChannelId
            )
        }

        val engine = ChunkedUploadEngine(
            chunkApi = mockApi,
            repository = ChunkedBackupRepository,
            database = database
        )

        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.Success)
        val success = result as ChunkedUploadResult.Success
        assertTrue(success.isAllChunksUploaded)
        assertEquals(1, success.completedChunks)

        // Verify parent in database is in VERIFYING state
        val backup = database.chunkedBackupDao().getById(success.backupId)
        assertNotNull(backup)
        assertEquals(ChunkedBackupStatus.VERIFYING, backup!!.status)
        assertEquals(1, backup.completedChunks)
        assertNotNull(backup.fileSha256)
    }

    @Test
    fun testSingleParentJobForLargeVideoDeduplication() = runBlocking {
        val testFile = createTestMediaFile("video2.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_video_2",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { fileName, size ->
            TelegramChunkUploadResult.Success(
                fileId = "remote_file_dedup",
                messageId = 2000L,
                fileSize = size,
                chatId = testChannelId
            )
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)

        val res1 = engine.processBackup(context, photo) as ChunkedUploadResult.Success
        val res2 = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        // Both calls should resolve to the exact same backupId
        assertEquals(res1.backupId, res2.backupId)

        // Verify only 1 record exists in chunked_backups
        val backups = database.chunkedBackupDao().getAll()
        assertEquals(1, backups.size)
    }

    @Test
    fun testSequentialChunkProcessingAndFileIdsPersisted() = runBlocking {
        // Create file requiring 3 chunks with 30 KB chunk size (or test multiple chunks)
        val testFile = createTestMediaFile("video3.mp4", 75 * 1024)
        val photo = Photo(
            localId = "local_video_3",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val uploadedChunks = mutableListOf<String>()
        val mockApi = createMockChunkApi { fileName, size ->
            uploadedChunks.add(fileName)
            TelegramChunkUploadResult.Success(
                fileId = "file_${uploadedChunks.size}",
                messageId = 3000L + uploadedChunks.size,
                fileSize = size,
                chatId = testChannelId
            )
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        // Verify sequential upload
        assertTrue(result.isAllChunksUploaded)
        val chunks = database.videoChunkDao().getByBackupId(result.backupId)
        for (chunk in chunks) {
            assertEquals(VideoChunkStatus.DONE, chunk.status)
            assertNotNull(chunk.telegramFileId)
            assertNotNull(chunk.telegramMessageId)
        }
    }

    @Test
    fun testCompletedChunkCountAndByteAccurateProgress() = runBlocking {
        val testFile = createTestMediaFile("video4.mp4", 120 * 1024)
        val photo = Photo(
            localId = "local_video_4",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { _, size ->
            TelegramChunkUploadResult.Success("id", 100L, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        val progress = ChunkedBackupRepository.getBackupProgress(result.backupId, database)
        assertNotNull(progress)
        assertEquals(120 * 1024L, progress!!.totalOriginalFileSize)
        assertEquals(120 * 1024L, progress.uploadedBytes) // Exact sum of chunks
        assertEquals(ChunkedBackupStatus.VERIFYING, progress.status)
    }

    @Test
    fun testRetryStatePersistenceOnTransientFailure() = runBlocking {
        val testFile = createTestMediaFile("video5.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_video_5",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { _, _ ->
            TelegramChunkUploadResult.NetworkError("Network socket dropped")
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.TransientError)
        val trans = result as ChunkedUploadResult.TransientError
        assertTrue(trans.scheduledRetryAt > System.currentTimeMillis())

        val chunks = database.videoChunkDao().getByBackupId(trans.backupId)
        assertEquals(1, chunks.size)
        assertEquals(VideoChunkStatus.RETRY_WAIT, chunks[0].status)
        assertEquals(1, chunks[0].retryCount)
    }

    @Test
    fun testRateLimitWaitPersistedAndHonoured() = runBlocking {
        val testFile = createTestMediaFile("video6.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_video_6",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { _, _ ->
            TelegramChunkUploadResult.RateLimited(45, "Rate limit: wait 45s")
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.RateLimited)
        val rl = result as ChunkedUploadResult.RateLimited
        assertEquals(45, rl.retryAfterSeconds)

        val chunks = database.videoChunkDao().getByBackupId(rl.backupId)
        assertEquals(VideoChunkStatus.RETRY_WAIT, chunks[0].status)
        assertTrue(chunks[0].scheduledRetryAt!! >= System.currentTimeMillis() + 44_000L)
    }

    @Test
    fun testConfigurationMismatchBotOrChannelChangedRejected() = runBlocking {
        val testFile = createTestMediaFile("video7.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_video_7",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        // Pre-create backup with different channel ID
        val oldBackup = ChunkedBackup(
            backupId = "backup_old_dest",
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video7.mp4",
            fileSize = 50 * 1024L,
            mimeType = "video/mp4",
            totalChunks = 1,
            completedChunks = 0,
            channelId = -999999999L, // Different channel!
            botId = testBotId
        )
        database.chunkedBackupDao().insert(oldBackup)

        val mockApi = createMockChunkApi { _, _ ->
            TelegramChunkUploadResult.Success("id", 1L, 100L, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.ConfigurationMismatch)
        val mismatch = result as ChunkedUploadResult.ConfigurationMismatch
        assertTrue(mismatch.reason.contains("Target destination changed"))
    }

    @Test
    fun testWorkerCancellationAtChunkBoundary() = runBlocking {
        val fileSize = 100 * 1024L
        val testFile = createTestMediaFile("video_cancel_bound.mp4", fileSize.toInt())
        val photo = Photo(
            localId = "local_cancel_bound",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )
        val backupId = "backup_cancel_bound"
        val chunkSize = 50 * 1024L
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video_cancel_bound.mp4",
            fileSize = fileSize,
            mimeType = "video/mp4",
            fileSha256 = "hash_cancel",
            chunkSize = chunkSize,
            totalChunks = 2,
            completedChunks = 0,
            status = ChunkedBackupStatus.UPLOADING,
            channelId = testChannelId,
            botId = testBotId
        )
        database.chunkedBackupDao().insert(backup)
        database.videoChunkDao().insertAll(
            listOf(
                VideoChunk(backupId = backupId, chunkIndex = 0, startOffset = 0L, endOffset = 50 * 1024L, chunkSize = 50 * 1024L, chunkSha256 = "h0"),
                VideoChunk(backupId = backupId, chunkIndex = 1, startOffset = 50 * 1024L, endOffset = 100 * 1024L, chunkSize = 50 * 1024L, chunkSha256 = "h1")
            )
        )

        var chunksUploaded = 0
        var shouldStop = false
        val mockApi = createMockChunkApi { _, size ->
            chunksUploaded++
            shouldStop = true // Trigger cancellation after 1st chunk
            TelegramChunkUploadResult.Success("id_$chunksUploaded", 100L + chunksUploaded, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(
            context = context,
            photo = photo,
            isStopped = { shouldStop }
        )

        // Result is Yielded
        assertTrue(result is ChunkedUploadResult.Yielded)
        val yielded = result as ChunkedUploadResult.Yielded
        assertEquals(1, yielded.completedChunks)
        assertEquals(2, yielded.totalChunks)
        assertTrue(yielded.reason.contains("stopped"))

        // Exactly 1 chunk uploaded, and parent remains in UPLOADING (not VERIFYING or DONE)
        val dbBackup = database.chunkedBackupDao().getById(yielded.backupId)
        assertNotNull(dbBackup)
        assertEquals(ChunkedBackupStatus.UPLOADING, dbBackup!!.status)
        assertEquals(1, dbBackup.completedChunks)
    }

    @Test
    fun testDurableContinuationWithoutDuplicateParentJobs() = runBlocking {
        val testFile = createTestMediaFile("video_continuation.mp4", 100 * 1024)
        val photo = Photo(
            localId = "local_continuation",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        var totalUploads = 0
        val mockApi = createMockChunkApi { _, size ->
            totalUploads++
            TelegramChunkUploadResult.Success("file_$totalUploads", 200L + totalUploads, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)

        // Run 1: Yield immediately after 0ms (approaching limit)
        val res1 = engine.processBackup(
            context = context,
            photo = photo,
            maxExecutionDurationMs = 0L // Forces yield at first chunk boundary
        )
        assertTrue(res1 is ChunkedUploadResult.Yielded)
        val backupId1 = (res1 as ChunkedUploadResult.Yielded).backupId

        // Run 2: Resume with standard duration
        val res2 = engine.processBackup(
            context = context,
            photo = photo
        )
        assertTrue(res2 is ChunkedUploadResult.Success)
        val success = res2 as ChunkedUploadResult.Success
        assertEquals(backupId1, success.backupId) // Exact same parent job reused!
        assertTrue(success.isAllChunksUploaded)

        // Ensure database has only 1 parent backup record
        val allBackups = database.chunkedBackupDao().getAll()
        assertEquals(1, allBackups.size)
        assertEquals(backupId1, allBackups[0].backupId)
        assertEquals(ChunkedBackupStatus.VERIFYING, allBackups[0].status)
    }

    @Test
    fun testNoSimultaneousProcessingOfSameBackup() = runBlocking {
        val testFile = createTestMediaFile("video_no_simultaneous.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_simultaneous",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { _, size ->
            TelegramChunkUploadResult.Success("id", 10L, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)

        // Multiple calls for the same photo resolve to the single parent backup
        val res1 = engine.processBackup(context, photo) as ChunkedUploadResult.Success
        val res2 = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        assertEquals(res1.backupId, res2.backupId)
        val backups = database.chunkedBackupDao().getAll()
        assertEquals(1, backups.size)
    }

    @Test
    fun testVerifyingBackupDiscoveryAfterUploadQueueCompletion() = runBlocking {
        val testFile = createTestMediaFile("video_verifying_discovery.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_verifying_disc",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        // Insert into upload_queue
        val queueItem = com.gallerysync.app.data.localdb.entities.UploadQueue(
            localId = photo.localId,
            pathUri = photo.pathUri,
            workerType = "periodic",
            mediaType = "VIDEO"
        )
        val queueId = database.uploadQueueDao().insert(queueItem)

        val mockApi = createMockChunkApi { _, size ->
            TelegramChunkUploadResult.Success("doc_disc", 999L, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        assertTrue(result.isAllChunksUploaded)

        // Simulate PeriodicPhotoBackupWorker completing the upload_queue item
        database.uploadQueueDao().markDone(queueId)

        // Even delete completed queue items to simulate user clearing history
        database.uploadQueueDao().deleteCompleted()
        val remainingQueue = database.uploadQueueDao().getAll()
        assertTrue(remainingQueue.isEmpty())

        // VERIFYING backup MUST remain discoverable in ChunkedBackupRepository!
        val verifyingBackups = ChunkedBackupRepository.getVerifyingBackups(database)
        assertEquals(1, verifyingBackups.size)
        assertEquals(result.backupId, verifyingBackups[0].backupId)
        assertEquals(ChunkedBackupStatus.VERIFYING, verifyingBackups[0].status)

        // getIncompleteBackups also discovers it
        val incomplete = ChunkedBackupRepository.getIncompleteBackups(database)
        assertEquals(1, incomplete.size)
        assertEquals(result.backupId, incomplete[0].backupId)
    }
}
