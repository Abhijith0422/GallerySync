package com.gallerysync.app.data.upload

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogChatInfo
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramDocument
import com.gallerysync.app.api.TelegramPinnedMessage
import com.gallerysync.app.data.catalog.BackupManifestManager
import com.gallerysync.app.data.catalog.MasterIndexManager
import com.gallerysync.app.data.catalog.models.ApplicationInfo
import com.gallerysync.app.data.catalog.models.BackupManifest
import com.gallerysync.app.data.catalog.models.BotIdentity
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.catalog.models.DestinationInfo
import com.gallerysync.app.data.catalog.models.ManifestChunkConfig
import com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.catalog.models.MediaDescriptor
import com.gallerysync.app.data.chunking.ChunkConfiguration
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.localdb.recovery.TelegramCatalogRecoveryResult
import com.gallerysync.app.data.restoration.ReconstructionResult
import com.gallerysync.app.data.restoration.RestorationExecutionResult
import com.gallerysync.app.data.restoration.VideoReconstructionEngine
import com.gallerysync.app.data.restoration.VideoRestorationManager
import com.gallerysync.app.utils.ContentHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase10_6UploadAndRecoveryValidationTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private val testChannelId = -1003924279260L
    private val testBotToken = "9051118019:ABC_TEST_TOKEN"
    private val testBotId = 9051118019L

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // SECTION 1: UPLOAD THRESHOLD & BOUNDARY VALIDATION (Section 3.3)
    // =========================================================================

    @Test
    fun boundaryValidation_allSevenBoundarySizes() {
        val engine = ChunkedUploadEngine.defaultInstance

        // 1. 16,777,215 bytes (16 MiB - 1 byte) -> Direct Upload
        val size1 = 16_777_215L
        assertFalse("16,777,215 bytes must use direct upload", engine.requiresChunkedBackup("VIDEO", size1))

        // 2. 16,777,216 bytes (exact 16 MiB) -> Direct Upload
        val size2 = 16_777_216L
        assertEquals(ChunkConfiguration.DIRECT_UPLOAD_MAX_BYTES, size2)
        assertFalse("16,777,216 bytes must use direct upload", engine.requiresChunkedBackup("VIDEO", size2))

        // 3. 16,777,217 bytes (16 MiB + 1 byte) -> Chunked Upload
        val size3 = 16_777_217L
        assertTrue("16,777,217 bytes must use chunked upload", engine.requiresChunkedBackup("VIDEO", size3))
        val chunks3 = ChunkedBackupRepository.calculateChunks("b3", size3, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(2, chunks3.size)
        assertEquals(0L, chunks3[0].startOffset)
        assertEquals(16_777_216L, chunks3[0].endOffset)
        assertEquals(16_777_216L, chunks3[0].chunkSize)
        assertEquals(16_777_216L, chunks3[1].startOffset)
        assertEquals(16_777_217L, chunks3[1].endOffset)
        assertEquals(1L, chunks3[1].chunkSize)
        assertEquals(size3, chunks3.sumOf { it.chunkSize })

        // 4. 20,000,000 bytes -> Chunked Upload
        val size4 = 20_000_000L
        assertTrue("20,000,000 bytes must use chunked upload", engine.requiresChunkedBackup("VIDEO", size4))
        val chunks4 = ChunkedBackupRepository.calculateChunks("b4", size4, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(2, chunks4.size)
        assertEquals(16_777_216L, chunks4[0].chunkSize)
        assertEquals(3_222_784L, chunks4[1].chunkSize)
        assertEquals(size4, chunks4.sumOf { it.chunkSize })
        assertTrue("Every chunk must remain <= 16 MiB", chunks4.all { it.chunkSize <= 16_777_216L })

        // 5. 20,000,001 bytes -> Chunked Upload
        val size5 = 20_000_001L
        assertTrue("20,000,001 bytes must use chunked upload", engine.requiresChunkedBackup("VIDEO", size5))
        val chunks5 = ChunkedBackupRepository.calculateChunks("b5", size5, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(2, chunks5.size)
        assertEquals(16_777_216L, chunks5[0].chunkSize)
        assertEquals(3_222_785L, chunks5[1].chunkSize)
        assertEquals(size5, chunks5.sumOf { it.chunkSize })
        assertTrue("Every chunk must remain <= 16 MiB", chunks5.all { it.chunkSize <= 16_777_216L })

        // 6. 50,000,000 bytes -> Chunked Upload
        val size6 = 50_000_000L
        assertTrue("50,000,000 bytes must use chunked upload", engine.requiresChunkedBackup("VIDEO", size6))
        val chunks6 = ChunkedBackupRepository.calculateChunks("b6", size6, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(3, chunks6.size)
        assertEquals(16_777_216L, chunks6[0].chunkSize)
        assertEquals(16_777_216L, chunks6[1].chunkSize)
        assertEquals(16_445_568L, chunks6[2].chunkSize)
        assertEquals(size6, chunks6.sumOf { it.chunkSize })
        assertTrue("Every chunk must remain <= 16 MiB", chunks6.all { it.chunkSize <= 16_777_216L })

        // 7. Video > 100 MiB (104,857,600 bytes) -> Chunked Upload
        val size7 = 100L * 1024L * 1024L // 104,857,600 bytes
        assertTrue("100 MiB video must use chunked upload", engine.requiresChunkedBackup("VIDEO", size7))
        val chunks7 = ChunkedBackupRepository.calculateChunks("b7", size7, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(7, chunks7.size)
        assertEquals(16_777_216L, chunks7[0].chunkSize)
        assertEquals(4_194_304L, chunks7[6].chunkSize)
        assertEquals(size7, chunks7.sumOf { it.chunkSize })
        assertTrue("Every chunk must remain <= 16 MiB", chunks7.all { it.chunkSize <= 16_777_216L })

        // Contiguous offset verification
        for (i in 0 until chunks7.size - 1) {
            assertEquals(chunks7[i].endOffset, chunks7[i + 1].startOffset)
        }
        assertEquals(0L, chunks7.first().startOffset)
        assertEquals(size7, chunks7.last().endOffset)

        // Images are never chunked regardless of size
        assertFalse(engine.requiresChunkedBackup("IMAGE", 30_000_000L))
    }

    @Test
    fun boundaryValidation_noIntegerOverflowOnMultiGigabyteFiles() {
        val largeFile = 10L * 1024L * 1024L * 1024L // 10 GiB
        val chunks = ChunkedBackupRepository.calculateChunks("large", largeFile, ChunkConfiguration.DEFAULT_CHUNK_SIZE)
        assertEquals(640, chunks.size)
        assertEquals(0L, chunks[0].startOffset)
        assertEquals(largeFile, chunks.last().endOffset)
        assertEquals(largeFile, chunks.sumOf { it.chunkSize })
    }

    // =========================================================================
    // SECTION 2: BACKUP LIFECYCLE & DEDUPLICATION (Section 4)
    // =========================================================================

    @Test
    fun backupLifecycle_queueDeduplication_skipsDoneAndRemoteMedia() = runBlocking {
        val photoDao = database.photoDao()
        val uploadQueueDao = database.uploadQueueDao()

        val donePhoto = Photo(
            localId = "photo_done",
            photoType = "jpg",
            pathUri = "content://media/1",
            mediaType = "IMAGE",
            uploadStatus = Photo.STATUS_DONE,
            remoteId = "tg_done_123"
        )
        val pendingPhoto = Photo(
            localId = "photo_pending",
            photoType = "jpg",
            pathUri = "content://media/2",
            mediaType = "IMAGE",
            uploadStatus = Photo.STATUS_NONE
        )
        photoDao.insertPhotos(donePhoto, pendingPhoto)

        val enqueued = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(donePhoto, pendingPhoto),
            workerType = "auto",
            database = database
        )

        assertEquals(1, enqueued)
        val queue = uploadQueueDao.getAll()
        assertEquals(1, queue.size)
        assertEquals("photo_pending", queue[0].localId)
    }

    @Test
    fun backupLifecycle_holdingProtection_skipsHoldingInAutoMode() = runBlocking {
        val photoDao = database.photoDao()
        val uploadQueueDao = database.uploadQueueDao()

        val holdingPhoto = Photo(
            localId = "photo_holding_1",
            photoType = "jpg",
            pathUri = "content://media/hold1",
            mediaType = "IMAGE",
            uploadStatus = Photo.STATUS_HOLDING
        )
        photoDao.insertPhotos(holdingPhoto)

        // Automatic enqueue must SKIP holding items
        val enqueuedAuto = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(holdingPhoto),
            workerType = "auto",
            database = database
        )
        assertEquals(0, enqueuedAuto)
        assertTrue(uploadQueueDao.getAll().isEmpty())

        // Manual backup action is allowed to enqueue holding items
        val enqueuedManual = UploadQueueRepository.enqueueMediaForBackup(
            context = context,
            photos = listOf(holdingPhoto),
            workerType = "manual_backup",
            database = database
        )
        assertEquals(1, enqueuedManual)
        assertEquals(1, uploadQueueDao.getAll().size)
    }

    // =========================================================================
    // SECTION 3: FRESH INSTALL RECOVERY VALIDATION (Section 5)
    // =========================================================================

    @Test
    fun freshInstallRecovery_simulatedEmptyDatabase_reconstructsAllCatalogRecords() = runBlocking {
        val isolatedDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            // Build mock MasterIndex with 1 manifest reference and 1 direct media record
            val manifestRef = ManifestReference(
                backupId = "backup_vid_rec",
                fileId = "tg_manifest_file_101",
                messageId = 5001L,
                fileName = "gallerysync_manifest_backup_vid_rec.json",
                sizeBytes = 2048L,
                sha256 = "manifest_sha_101",
                mediaSha256 = "full_video_sha",
                displayName = "restored_video.mp4"
            )

            val directRecord = CatalogMediaRecord(
                mediaSha256 = "direct_photo_sha",
                displayName = "photo_direct.jpg",
                sizeBytes = 2_048_000L,
                mediaType = "IMAGE",
                telegramFileId = "tg_photo_file_202",
                telegramMessageId = 5002L,
                backupTimestamp = 1700000000000L,
                backupStatus = "DONE",
                localId = "local_photo_202"
            )

            val masterIndex = MasterIndex(
                format = MasterIndex.FORMAT_IDENTIFIER,
                version = MasterIndex.CURRENT_VERSION,
                generation = 5L,
                updatedAt = 1700000000000L,
                botId = testBotId,
                channelId = testChannelId,
                manifests = listOf(manifestRef),
                shards = emptyList(),
                activeMediaRecords = listOf(directRecord)
            )

            val mapper = ObjectMapper()
            val masterIndexJsonBytes = mapper.writeValueAsBytes(masterIndex)

            val backupManifest = BackupManifest(
                format = BackupManifest.FORMAT_IDENTIFIER,
                version = BackupManifest.CURRENT_VERSION,
                backupId = "backup_vid_rec",
                createdAt = 1700000000000L,
                application = ApplicationInfo(name = "GallerySync", packageName = "com.gallerysync.app"),
                bot = BotIdentity(id = testBotId, username = "TestBot"),
                destination = DestinationInfo(chatId = testChannelId, topicId = null),
                media = MediaDescriptor(
                    originalName = "restored_video.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 17_825_792L,
                    width = 1920,
                    height = 1080,
                    durationMs = 15000L,
                    sha256 = "full_video_sha"
                ),
                chunkConfiguration = ManifestChunkConfig(16_777_216L, 2),
                chunks = listOf(
                    ManifestChunkDescriptor(0, 0L, 16_777_216L, 16_777_216L, "chunk0_sha", "tg_c0_file", 5003L),
                    ManifestChunkDescriptor(1, 16_777_216L, 17_825_792L, 1_048_576L, "chunk1_sha", "tg_c1_file", 5004L)
                )
            )
            val manifestJsonBytes = mapper.writeValueAsBytes(backupManifest)

            // Setup Mock TelegramCatalogApi
            val mockCatalogApi = object : TelegramCatalogApi() {
                override suspend fun getChatInfo(botToken: String, channelId: Long, baseUrl: String): TelegramCatalogResult<TelegramCatalogChatInfo> {
                    val doc = TelegramDocument(
                        fileId = "master_index_file_id",
                        fileUniqueId = "uniq_idx",
                        fileName = "master_index_gen_000005.json",
                        fileSize = masterIndexJsonBytes.size.toLong(),
                        mimeType = "application/json"
                    )
                    val msg = TelegramPinnedMessage(
                        messageId = 9999L,
                        date = 1700000000L,
                        document = doc
                    )
                    return TelegramCatalogResult.Success(TelegramCatalogChatInfo(id = testChannelId, type = "channel", title = "Backup", pinnedMessage = msg))
                }

                override suspend fun downloadFileByFileId(botToken: String, fileId: String, maxSizeBytes: Long, baseUrl: String): TelegramCatalogResult<ByteArray> {
                    return when (fileId) {
                        "master_index_file_id" -> TelegramCatalogResult.Success(masterIndexJsonBytes)
                        "tg_manifest_file_101" -> TelegramCatalogResult.Success(manifestJsonBytes)
                        else -> TelegramCatalogResult.InvalidResponse("Unknown fileId: $fileId")
                    }
                }
            }

            // Execute fresh install recovery on empty isolated database
            val recoveryResult = InstallRecoveryManager.recoverCatalogFromTelegram(
                context = context,
                botToken = testBotToken,
                channelId = testChannelId,
                database = isolatedDb,
                catalogApi = mockCatalogApi,
                indexManager = MasterIndexManager.defaultInstance
            )

            assertTrue("Recovery must succeed", recoveryResult is TelegramCatalogRecoveryResult.Success)
            val success = recoveryResult as TelegramCatalogRecoveryResult.Success
            assertEquals(5L, success.generation)
            assertEquals(1, success.manifestsRecovered)
            assertEquals(1, success.mediaRecordsRecovered)

            // Verify isolated DB contents
            val remotePhotos = isolatedDb.remotePhotoDao().getAll()
            assertEquals(2, remotePhotos.size) // 1 direct + 1 manifest cross-reference
            assertTrue(remotePhotos.any { it.remoteId == "tg_photo_file_202" && it.mediaType == "IMAGE" })
            assertTrue(remotePhotos.any { it.remoteId == "tg_manifest_file_101" && it.mediaType == "VIDEO" })

            val chunkedBackups = isolatedDb.chunkedBackupDao().getAll()
            assertEquals(1, chunkedBackups.size)
            assertEquals("backup_vid_rec", chunkedBackups[0].backupId)
            assertEquals(ChunkedBackupStatus.DONE, chunkedBackups[0].status)
            assertEquals("full_video_sha", chunkedBackups[0].fileSha256)
            assertEquals(17_825_792L, chunkedBackups[0].fileSize)

            val chunks = isolatedDb.videoChunkDao().getByBackupId("backup_vid_rec")
            assertEquals(2, chunks.size)
            assertEquals("tg_c0_file", chunks[0].telegramFileId)
            assertEquals(5003L, chunks[0].telegramMessageId)
            assertEquals("tg_c1_file", chunks[1].telegramFileId)
            assertEquals(5004L, chunks[1].telegramMessageId)

            val catalog = isolatedDb.backupCatalogDao().getByChannelId(testChannelId)
            assertNotNull(catalog)
            assertEquals(5L, catalog!!.lastConfirmedGeneration)
            assertEquals("COMMITTED", catalog.syncStatus)

        } finally {
            isolatedDb.close()
        }
    }

    @Test
    fun freshInstallRecovery_identityMismatch_failsCleanly() = runBlocking {
        val isolatedDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val masterIndex = MasterIndex(
                format = MasterIndex.FORMAT_IDENTIFIER,
                version = MasterIndex.CURRENT_VERSION,
                generation = 1L,
                channelId = -9999999999L, // MISMATCHED channel ID!
                botId = testBotId,
                updatedAt = 1700000000000L,
                manifests = emptyList(),
                shards = emptyList(),
                activeMediaRecords = emptyList()
            )
            val mapper = ObjectMapper()
            val masterIndexBytes = mapper.writeValueAsBytes(masterIndex)

            val mockCatalogApi = object : TelegramCatalogApi() {
                override suspend fun getChatInfo(botToken: String, channelId: Long, baseUrl: String): TelegramCatalogResult<TelegramCatalogChatInfo> {
                    val doc = TelegramDocument(fileId = "idx_mismatch", fileUniqueId = "u", fileName = "master_index.json", mimeType = "application/json", fileSize = masterIndexBytes.size.toLong())
                    val msg = TelegramPinnedMessage(messageId = 1L, date = 1L, document = doc)
                    return TelegramCatalogResult.Success(TelegramCatalogChatInfo(id = testChannelId, type = "channel", title = "Backup", pinnedMessage = msg))
                }
                override suspend fun downloadFileByFileId(botToken: String, fileId: String, maxSizeBytes: Long, baseUrl: String): TelegramCatalogResult<ByteArray> {
                    return TelegramCatalogResult.Success(masterIndexBytes)
                }
            }

            val result = InstallRecoveryManager.recoverCatalogFromTelegram(
                context = context,
                botToken = testBotToken,
                channelId = testChannelId,
                database = isolatedDb,
                catalogApi = mockCatalogApi
            )

            assertTrue(result is TelegramCatalogRecoveryResult.IdentityMismatch)
            val mismatch = result as TelegramCatalogRecoveryResult.IdentityMismatch
            assertTrue(mismatch.reason.contains("does not match target channel"))
        } finally {
            isolatedDb.close()
        }
    }

    // =========================================================================
    // SECTION 4: RESTORATION & INTEGRITY TESTING (Section 7)
    // =========================================================================

    @Test
    fun restoration_chunkedVideo_reconstructionAndShaMatch() = runBlocking {
        val chunk0Data = ByteArray(1024 * 64) { 1 }
        val chunk1Data = ByteArray(1024 * 32) { 2 }
        val fullData = chunk0Data + chunk1Data
        val fullSha = ContentHasher.computeHash(fullData)
        val c0Sha = ContentHasher.computeHash(chunk0Data)
        val c1Sha = ContentHasher.computeHash(chunk1Data)

        val c0File = tempFolder.newFile("chunk_0.bin")
        FileOutputStream(c0File).use { it.write(chunk0Data) }
        val c1File = tempFolder.newFile("chunk_1.bin")
        FileOutputStream(c1File).use { it.write(chunk1Data) }

        val outputFile = tempFolder.newFile("reconstructed_output.mp4")

        val engine = VideoReconstructionEngine()
        val descriptors = listOf(
            ManifestChunkDescriptor(0, 0L, 1024 * 64L, 1024 * 64L, c0Sha, "tg0", 1L),
            ManifestChunkDescriptor(1, 1024 * 64L, fullData.size.toLong(), 1024 * 32L, c1Sha, "tg1", 2L)
        )
        val chunkFiles = mapOf(0 to c0File, 1 to c1File)

        val res = engine.reconstruct(
            chunkFiles = chunkFiles,
            chunkDescriptors = descriptors,
            expectedTotalSize = fullData.size.toLong(),
            expectedFullSha256 = fullSha,
            outputFile = outputFile
        )

        assertTrue("Expected Success but got $res", res is ReconstructionResult.Success)
        val success = res as ReconstructionResult.Success
        assertEquals(fullSha, success.fullSha256)
        assertEquals(fullData.size.toLong(), success.totalBytes)
        assertEquals(fullData.size.toLong(), outputFile.length())
    }

    @Test
    fun restoration_corruptedChunk_failsChecksumVerification() = runBlocking {
        val chunk0Data = ByteArray(1024 * 64) { 1 }
        val corruptedChunk0 = ByteArray(1024 * 64) { 9 } // Corrupted!
        val c0File = tempFolder.newFile("chunk_corrupt_0.bin")
        FileOutputStream(c0File).use { it.write(corruptedChunk0) }

        val outputFile = File(tempFolder.root, "corrupt_output.mp4")
        if (outputFile.exists()) outputFile.delete()

        val engine = VideoReconstructionEngine()
        val descriptors = listOf(
            ManifestChunkDescriptor(0, 0L, 1024 * 64L, 1024 * 64L, ContentHasher.computeHash(chunk0Data), "tg0", 1L)
        )

        val res = engine.reconstruct(
            chunkFiles = mapOf(0 to c0File),
            chunkDescriptors = descriptors,
            expectedTotalSize = chunk0Data.size.toLong(),
            expectedFullSha256 = ContentHasher.computeHash(chunk0Data),
            outputFile = outputFile
        )

        assertTrue("Expected VerificationFailed but got $res", res is ReconstructionResult.VerificationFailed)
        assertFalse(outputFile.exists()) // Corrupted output deleted
    }

    @Test
    fun restoration_directVideoExceedingHostedLimit_rejectedWithNonRetryableError() = runBlocking {
        val manager = VideoRestorationManager(database = database)
        val job = manager.createOrGetJob(
            context = context,
            remoteId = "direct_oversized",
            fileName = "large_direct.mp4",
            fileSize = 35_000_000L, // 35 MB > 20 MB hosted limit!
            totalChunks = 1
        )

        val result = manager.executeRestoration(
            context = context,
            restorationId = job.restorationId,
            botToken = testBotToken
        )

        assertTrue(result is RestorationExecutionResult.Error)
        val err = result as RestorationExecutionResult.Error
        assertFalse(err.isRetryable)
        assertTrue(err.message.contains("20000000") || err.message.contains("20 MB") || err.message.contains("Telegram hosted download limit"))
    }
}
