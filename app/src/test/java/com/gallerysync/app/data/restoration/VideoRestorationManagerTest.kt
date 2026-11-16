package com.gallerysync.app.data.restoration

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoRestorationManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createOrGetJob_returnsExistingActiveJobWithoutDuplicates() = runBlocking {
        val manager = VideoRestorationManager(database = database)

        val job1 = manager.createOrGetJob(
            context = context,
            backupId = "backup_abc",
            fileName = "my_video.mp4",
            fileSize = 1000L,
            totalChunks = 1
        )

        val job2 = manager.createOrGetJob(
            context = context,
            backupId = "backup_abc",
            fileName = "my_video.mp4",
            fileSize = 1000L,
            totalChunks = 1
        )

        assertEquals(job1.restorationId, job2.restorationId)
        val allJobs = database.videoRestorationDao().getAll()
        assertEquals(1, allJobs.size)
    }

    @Test
    fun chunkedVideoRestoration_successfulEndToEnd() = runBlocking {
        val totalSize = 100 * 1024 // 100 KiB
        val fullVideo = Random.nextBytes(totalSize)
        val fullHash = sha256(fullVideo)

        val c0 = fullVideo.copyOfRange(0, 50 * 1024)
        val c1 = fullVideo.copyOfRange(50 * 1024, totalSize)
        val h0 = sha256(c0)
        val h1 = sha256(c1)

        val backupId = "backup_test_end2end"

        // Insert Room ChunkedBackup and VideoChunk records
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = "local_1",
            pathUri = "content://media/1",
            fileName = "movie.mp4",
            fileSize = totalSize.toLong(),
            mimeType = "video/mp4",
            fileSha256 = fullHash,
            chunkSize = 50 * 1024L,
            totalChunks = 2,
            completedChunks = 2,
            status = ChunkedBackupStatus.DONE,
            channelId = -100111L,
            botId = 9999L
        )
        database.chunkedBackupDao().insert(backup)

        val chunk0 = VideoChunk(
            backupId = backupId,
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 50 * 1024L,
            chunkSize = 50 * 1024L,
            chunkSha256 = h0,
            telegramFileId = "tg_chunk_0",
            telegramMessageId = 11L,
            status = VideoChunkStatus.DONE
        )
        val chunk1 = VideoChunk(
            backupId = backupId,
            chunkIndex = 1,
            startOffset = 50 * 1024L,
            endOffset = totalSize.toLong(),
            chunkSize = 50 * 1024L,
            chunkSha256 = h1,
            telegramFileId = "tg_chunk_1",
            telegramMessageId = 12L,
            status = VideoChunkStatus.DONE
        )
        database.videoChunkDao().insertAll(listOf(chunk0, chunk1))

        // Create mock downloader
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("getFile")) {
                    val fid = if (url.contains("tg_chunk_0")) "tg_chunk_0" else "tg_chunk_1"
                    val path = if (fid == "tg_chunk_0") "videos/c0.bin" else "videos/c1.bin"
                    val json = """{"ok":true,"result":{"file_id":"$fid","file_size":51200,"file_path":"$path"}}"""
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(json.toResponseBody("application/json".toMediaType())).build()
                } else if (url.contains("c0.bin")) {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(c0.toResponseBody("application/octet-stream".toMediaType())).build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(c1.toResponseBody("application/octet-stream".toMediaType())).build()
                }
            }
            .build()

        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)
        val manager = VideoRestorationManager(
            database = database,
            downloader = downloader,
            reconstructionEngine = VideoReconstructionEngine(),
            catalogApi = catalogApi
        )

        // Create destination file
        val safDestinationFile = tempFolder.newFile("saf_restored_movie.mp4")
        val safUri = Uri.fromFile(safDestinationFile)

        val job = manager.createOrGetJob(
            context = context,
            backupId = backupId,
            fileName = "movie.mp4",
            fileSize = totalSize.toLong(),
            fileSha256 = fullHash,
            totalChunks = 2,
            destinationUri = safUri.toString()
        )

        val executionResult = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = "9999:fake_token"
        )

        assertTrue("Expected Success but got: $executionResult", executionResult is RestorationExecutionResult.Success)
        val success = executionResult as RestorationExecutionResult.Success
        assertEquals(totalSize.toLong(), success.totalBytes)
        assertEquals(fullHash, success.fullSha256)

        // Verify restored file byte equality
        assertTrue(safDestinationFile.exists())
        assertArrayEquals(fullVideo, safDestinationFile.readBytes())

        // Verify Room database job status
        val finishedJob = database.videoRestorationDao().getById(job.restorationId)
        assertNotNull(finishedJob)
        assertEquals(RestorationStatus.COMPLETED, finishedJob!!.status)
        assertEquals(2, finishedJob.verifiedChunks)
        assertEquals(totalSize.toLong(), finishedJob.downloadedBytes)
    }

    @Test
    fun chunkedVideoRestoration_resumability_retainsPreExistingVerifiedChunk() = runBlocking {
        val totalSize = 60 * 1024
        val fullVideo = Random.nextBytes(totalSize)
        val fullHash = sha256(fullVideo)

        val c0 = fullVideo.copyOfRange(0, 30 * 1024)
        val c1 = fullVideo.copyOfRange(30 * 1024, totalSize)

        val backupId = "backup_resumable_test"

        val backup = ChunkedBackup(
            backupId = backupId,
            localId = "local_resumable",
            pathUri = "content://media/2",
            fileName = "resumable.mp4",
            fileSize = totalSize.toLong(),
            mimeType = "video/mp4",
            fileSha256 = fullHash,
            chunkSize = 30 * 1024L,
            totalChunks = 2,
            completedChunks = 2,
            status = ChunkedBackupStatus.DONE,
            channelId = -100111L,
            botId = 9999L
        )
        database.chunkedBackupDao().insert(backup)

        val chunk0 = VideoChunk(
            backupId = backupId,
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 30 * 1024L,
            chunkSize = 30 * 1024L,
            chunkSha256 = sha256(c0),
            telegramFileId = "tg_chunk_0",
            telegramMessageId = 101L,
            status = VideoChunkStatus.DONE
        )
        val chunk1 = VideoChunk(
            backupId = backupId,
            chunkIndex = 1,
            startOffset = 30 * 1024L,
            endOffset = totalSize.toLong(),
            chunkSize = 30 * 1024L,
            chunkSha256 = sha256(c1),
            telegramFileId = "tg_chunk_1",
            telegramMessageId = 102L,
            status = VideoChunkStatus.DONE
        )
        database.videoChunkDao().insertAll(listOf(chunk0, chunk1))

        var c0DownloadCalled = false
        var c1DownloadCalled = false

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("getFile")) {
                    val fid = if (url.contains("tg_chunk_0")) "tg_chunk_0" else "tg_chunk_1"
                    val path = if (fid == "tg_chunk_0") "videos/c0.bin" else "videos/c1.bin"
                    val json = """{"ok":true,"result":{"file_id":"$fid","file_size":30720,"file_path":"$path"}}"""
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(json.toResponseBody("application/json".toMediaType())).build()
                } else if (url.contains("c0.bin")) {
                    c0DownloadCalled = true
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(c0.toResponseBody("application/octet-stream".toMediaType())).build()
                } else {
                    c1DownloadCalled = true
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(c1.toResponseBody("application/octet-stream".toMediaType())).build()
                }
            }
            .build()

        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)
        val manager = VideoRestorationManager(
            database = database,
            downloader = downloader,
            reconstructionEngine = VideoReconstructionEngine(),
            catalogApi = catalogApi
        )

        val safDestinationFile = tempFolder.newFile("saf_resumable.mp4")
        val job = manager.createOrGetJob(
            context = context,
            backupId = backupId,
            fileName = "resumable.mp4",
            fileSize = totalSize.toLong(),
            fileSha256 = fullHash,
            totalChunks = 2,
            destinationUri = Uri.fromFile(safDestinationFile).toString()
        )

        // Pre-stage chunk 0 on disk with correct size and hash
        val stagingDir = File(job.stagingDirectory)
        val chunksDir = File(stagingDir, RestorationConfiguration.CHUNK_STAGE_SUBDIR).apply { mkdirs() }
        File(chunksDir, "chunk_0.bin").writeBytes(c0)

        val result = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = "9999:fake_token"
        )

        assertTrue("Expected Success but got: $result", result is RestorationExecutionResult.Success)
        // Chunk 0 was already verified on disk, so it MUST NOT be re-downloaded
        assertFalse("Chunk 0 must NOT be downloaded again when verified on disk", c0DownloadCalled)
        assertTrue("Chunk 1 should be downloaded", c1DownloadCalled)
        assertArrayEquals(fullVideo, safDestinationFile.readBytes())
    }

    @Test
    fun restoration_cooperativeCancellation() = runBlocking {
        val backupId = "backup_cancel"
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = "local_cancel",
            pathUri = "content://media/3",
            fileName = "cancel.mp4",
            fileSize = 1000L,
            mimeType = "video/mp4",
            fileSha256 = "dummy_sha",
            chunkSize = 1000L,
            totalChunks = 1,
            completedChunks = 1,
            status = ChunkedBackupStatus.DONE,
            channelId = -100111L,
            botId = 9999L
        )
        database.chunkedBackupDao().insert(backup)

        val manager = VideoRestorationManager(database = database)
        val job = manager.createOrGetJob(
            context = context,
            backupId = backupId,
            fileName = "cancel.mp4",
            fileSize = 1000L,
            totalChunks = 1
        )

        val result = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = "9999:fake_token",
            isStopped = { true }
        )

        assertTrue(result is RestorationExecutionResult.Cancelled)
    }

    @Test
    fun directVideo_exceeding20MB_failsWithHostedLimitError() = runBlocking {
        val remoteId = "direct_large_video_id"
        val manager = VideoRestorationManager(database = database)
        val job = manager.createOrGetJob(
            context = context,
            remoteId = remoteId,
            fileName = "large_direct.mp4",
            fileSize = 35_000_000L, // 35 MB direct video (> 20 MB hosted ceiling)
            totalChunks = 1
        )

        val result = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = "9999:fake_token"
        )

        assertTrue("Expected Error but got: $result", result is RestorationExecutionResult.Error)
        val errorResult = result as RestorationExecutionResult.Error
        assertFalse("Hosted limit failure must not be retryable", errorResult.isRetryable)
        assertTrue("Error must mention 20 MB hosted download limit", errorResult.message.contains("20000000") || errorResult.message.contains("20 MB"))
    }

    @Test
    fun chunkedRestoration_fastPath_skipsDownloadWhenOutputFileAlreadyReconstructed() = runBlocking {
        val totalSize = 100 * 1024
        val fullVideo = Random.nextBytes(totalSize)
        val fullHash = sha256(fullVideo)

        val backupId = "backup_fast_path"
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = "local_fast_path",
            pathUri = "content://media/fast",
            fileName = "fast.mp4",
            fileSize = totalSize.toLong(),
            mimeType = "video/mp4",
            fileSha256 = fullHash,
            chunkSize = 50 * 1024L,
            totalChunks = 2,
            completedChunks = 2,
            status = ChunkedBackupStatus.DONE,
            channelId = -100111L,
            botId = 9999L
        )
        database.chunkedBackupDao().insert(backup)

        val chunk0 = VideoChunk(
            backupId = backupId,
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 50 * 1024L,
            chunkSize = 50 * 1024L,
            chunkSha256 = "dummy0",
            telegramFileId = "tg0",
            telegramMessageId = 101L,
            status = VideoChunkStatus.DONE
        )
        val chunk1 = VideoChunk(
            backupId = backupId,
            chunkIndex = 1,
            startOffset = 50 * 1024L,
            endOffset = totalSize.toLong(),
            chunkSize = 50 * 1024L,
            chunkSha256 = "dummy1",
            telegramFileId = "tg1",
            telegramMessageId = 102L,
            status = VideoChunkStatus.DONE
        )
        database.videoChunkDao().insertAll(listOf(chunk0, chunk1))

        var networkCalls = 0
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCalls++
                chain.proceed(chain.request())
            }
            .build()

        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)
        val manager = VideoRestorationManager(
            database = database,
            downloader = downloader,
            reconstructionEngine = VideoReconstructionEngine(),
            catalogApi = catalogApi
        )

        val safDestinationFile = tempFolder.newFile("saf_fast_output.mp4")
        val job = manager.createOrGetJob(
            context = context,
            backupId = backupId,
            fileName = "fast.mp4",
            fileSize = totalSize.toLong(),
            fileSha256 = fullHash,
            totalChunks = 2,
            destinationUri = Uri.fromFile(safDestinationFile).toString()
        )

        // Pre-stage the reconstructed output file directly
        val stagingDir = File(job.stagingDirectory)
        val outputDir = File(stagingDir, RestorationConfiguration.RECONSTRUCT_STAGE_SUBDIR).apply { mkdirs() }
        val outputFile = File(outputDir, "${job.originalFileName}.reconstructed")
        outputFile.writeBytes(fullVideo)

        val result = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = "9999:fake_token"
        )

        assertTrue("Expected Success but got: $result", result is RestorationExecutionResult.Success)
        assertEquals("Zero network calls should be made when output is already reconstructed", 0, networkCalls)
        assertArrayEquals(fullVideo, safDestinationFile.readBytes())
    }
}
