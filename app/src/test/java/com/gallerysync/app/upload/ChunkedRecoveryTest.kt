package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.api.TelegramChunkApi
import com.gallerysync.app.api.TelegramChunkUploadResult
import com.gallerysync.app.data.chunking.ChunkDescriptor
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
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ChunkedRecoveryTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private val testChannelId = -1001234567890L
    private val testBotToken = "12345678:ABC-DEF_recovery_token"
    private val testBotId = 12345678L

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
    fun testInterruptionAfterChunk5ResumesFromChunk6() = runBlocking {
        val fileSize = 100 * 1024L
        val testFile = createTestMediaFile("video_resume_5.mp4", fileSize.toInt())
        val photo = Photo(
            localId = "local_rec_5",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val backupId = "rec_backup_5"
        val totalChunks = 10
        val chunkSize = fileSize / totalChunks

        // Pre-populate backup with chunks 0..5 marked DONE
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video_resume_5.mp4",
            fileSize = fileSize,
            mimeType = "video/mp4",
            fileSha256 = "dummy_sha256",
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            completedChunks = 6, // 0 through 5 done
            status = ChunkedBackupStatus.UPLOADING,
            channelId = testChannelId,
            botId = testBotId
        )
        database.chunkedBackupDao().insert(backup)

        val chunks = (0 until totalChunks).map { i ->
            VideoChunk(
                backupId = backupId,
                chunkIndex = i,
                startOffset = i * chunkSize,
                endOffset = (i + 1) * chunkSize,
                chunkSize = chunkSize,
                chunkSha256 = "hash_$i",
                telegramFileId = if (i <= 5) "file_id_$i" else null,
                telegramMessageId = if (i <= 5) 1000L + i else null,
                status = if (i <= 5) VideoChunkStatus.DONE else VideoChunkStatus.PENDING
            )
        }
        database.videoChunkDao().insertAll(chunks)

        // Track which chunks are uploaded in this resume session
        val uploadedInThisRun = mutableListOf<String>()
        val mockApi = createMockChunkApi { fileName, size ->
            uploadedInThisRun.add(fileName)
            TelegramChunkUploadResult.Success(
                fileId = "new_file_${uploadedInThisRun.size}",
                messageId = 5000L + uploadedInThisRun.size,
                fileSize = size,
                chatId = testChannelId
            )
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        assertTrue(result.isAllChunksUploaded)
        // Chunks 0..5 were already DONE, so only chunks 6, 7, 8, 9 (4 chunks) should have been uploaded!
        assertEquals(4, uploadedInThisRun.size)
        assertTrue(uploadedInThisRun[0].contains("chunk_00006"))
        assertTrue(uploadedInThisRun[3].contains("chunk_00009"))
    }

    @Test
    fun testInterruptionAfterChunk37ResumesFromChunk38() = runBlocking {
        val fileSize = 200 * 1024L
        val testFile = createTestMediaFile("video_resume_37.mp4", fileSize.toInt())
        val photo = Photo(
            localId = "local_rec_37",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val backupId = "rec_backup_37"
        val totalChunks = 50
        val chunkSize = fileSize / totalChunks

        // Pre-populate backup with chunks 0..37 marked DONE
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video_resume_37.mp4",
            fileSize = fileSize,
            mimeType = "video/mp4",
            fileSha256 = "dummy_sha256",
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            completedChunks = 38,
            status = ChunkedBackupStatus.UPLOADING,
            channelId = testChannelId,
            botId = testBotId
        )
        database.chunkedBackupDao().insert(backup)

        val chunks = (0 until totalChunks).map { i ->
            VideoChunk(
                backupId = backupId,
                chunkIndex = i,
                startOffset = i * chunkSize,
                endOffset = (i + 1) * chunkSize,
                chunkSize = chunkSize,
                chunkSha256 = "hash_$i",
                telegramFileId = if (i <= 37) "file_id_$i" else null,
                telegramMessageId = if (i <= 37) 1000L + i else null,
                status = if (i <= 37) VideoChunkStatus.DONE else VideoChunkStatus.PENDING
            )
        }
        database.videoChunkDao().insertAll(chunks)

        val uploadedInThisRun = mutableListOf<String>()
        val mockApi = createMockChunkApi { fileName, size ->
            uploadedInThisRun.add(fileName)
            TelegramChunkUploadResult.Success(
                fileId = "new_file_${uploadedInThisRun.size}",
                messageId = 6000L + uploadedInThisRun.size,
                fileSize = size,
                chatId = testChannelId
            )
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        assertTrue(result.isAllChunksUploaded)
        // 50 total chunks - 38 already done = 12 remaining chunks (38 to 49)
        assertEquals(12, uploadedInThisRun.size)
        assertTrue(uploadedInThisRun[0].contains("chunk_00038"))
        assertTrue(uploadedInThisRun.last().contains("chunk_00049"))
    }

    @Test
    fun testProcessRestartRecoversStaleUploadingChunks() = runBlocking {
        val testFile = createTestMediaFile("video_stale.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_stale",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val backupId = "backup_stale"
        val backup = ChunkedBackup(
            backupId = backupId,
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video_stale.mp4",
            fileSize = 50 * 1024L,
            mimeType = "video/mp4",
            fileSha256 = "sha256",
            totalChunks = 1,
            completedChunks = 0,
            status = ChunkedBackupStatus.UPLOADING,
            channelId = testChannelId,
            botId = testBotId
        )
        database.chunkedBackupDao().insert(backup)

        // Chunk was left in UPLOADING state due to sudden crash
        val chunk = VideoChunk(
            backupId = backupId,
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 50 * 1024L,
            chunkSize = 50 * 1024L,
            chunkSha256 = "hash_0",
            status = VideoChunkStatus.UPLOADING
        )
        val chunkId = database.videoChunkDao().insert(chunk)

        val mockApi = createMockChunkApi { _, size ->
            TelegramChunkUploadResult.Success("recovered_file_id", 777L, size, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo) as ChunkedUploadResult.Success

        // Stale chunk was recovered back to PENDING and then successfully uploaded to DONE!
        assertTrue(result.isAllChunksUploaded)
        val updatedChunk = database.videoChunkDao().getById(chunkId)
        assertEquals(VideoChunkStatus.DONE, updatedChunk?.status)
        assertEquals("recovered_file_id", updatedChunk?.telegramFileId)
    }

    @Test
    fun testSourceModificationAfterHashingAbortsBackup() = runBlocking {
        val testFile = createTestMediaFile("video_mod.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_mod",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        // Pre-create backup with size 50 KB
        val backup = ChunkedBackup(
            backupId = "backup_mod",
            localId = photo.localId,
            pathUri = photo.pathUri,
            fileName = "video_mod.mp4",
            fileSize = 50 * 1024L,
            mimeType = "video/mp4",
            fileSha256 = "valid_hash",
            totalChunks = 1,
            completedChunks = 0,
            status = ChunkedBackupStatus.UPLOADING,
            channelId = testChannelId,
            botId = testBotId
        )
        database.chunkedBackupDao().insert(backup)

        // Now modify the source file on disk (append bytes so size changes to 60 KB)
        FileOutputStream(testFile, true).use { fos ->
            fos.write(ByteArray(10 * 1024))
        }

        val mockApi = createMockChunkApi { _, _ ->
            TelegramChunkUploadResult.Success("id", 1L, 100L, testChannelId)
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.SourceModified)
        val mod = result as ChunkedUploadResult.SourceModified
        assertTrue(mod.reason.contains("Source file size changed"))

        val dbBackup = database.chunkedBackupDao().getById(backup.backupId)
        assertEquals(ChunkedBackupStatus.FAILED, dbBackup?.status)
    }

    @Test
    fun testAmbiguousTimeoutDoesNotMarkChunkDone() = runBlocking {
        val testFile = createTestMediaFile("video_timeout.mp4", 50 * 1024)
        val photo = Photo(
            localId = "local_timeout",
            photoType = "mp4",
            pathUri = testFile.toURI().toString(),
            mediaType = "VIDEO"
        )

        val mockApi = createMockChunkApi { _, _ ->
            TelegramChunkUploadResult.NetworkError("Socket timeout after server accepted bytes")
        }

        val engine = ChunkedUploadEngine(mockApi, ChunkedBackupRepository, database)
        val result = engine.processBackup(context, photo)

        assertTrue(result is ChunkedUploadResult.TransientError)
        val trans = result as ChunkedUploadResult.TransientError

        val chunks = database.videoChunkDao().getByBackupId(trans.backupId)
        // Must NOT be marked DONE on ambiguous timeout!
        assertEquals(VideoChunkStatus.RETRY_WAIT, chunks[0].status)
        assertEquals(null, chunks[0].telegramFileId)
    }
}
