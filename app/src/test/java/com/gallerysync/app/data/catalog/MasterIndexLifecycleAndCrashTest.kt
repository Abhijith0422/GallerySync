package com.gallerysync.app.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogChatInfo
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramCatalogUploadResult
import com.gallerysync.app.api.TelegramDocument
import com.gallerysync.app.api.TelegramPinnedMessage
import com.gallerysync.app.data.catalog.models.CatalogMediaRecord
import com.gallerysync.app.data.catalog.models.ManifestReference
import com.gallerysync.app.data.catalog.models.MasterIndex
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackup
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.PendingCatalogEntry
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.VideoChunk
import com.gallerysync.app.data.localdb.entities.VideoChunkStatus
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.localdb.recovery.TelegramCatalogRecoveryResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterIndexLifecycleAndCrashTest {

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val botToken = "123456789:ABCdef-Token"
    private val channelId = -1001234567890L
    private val botId = 123456789L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.allowUnencryptedFallbackForTesting = true
        Preferences.init(context)

        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testTwoStageBackupLifecycle_manifestUploadDoesNotMarkDoneUntilMasterIndexConfirmed(): Unit = runBlocking {
        val chunkedBackupDao = database.chunkedBackupDao()
        val videoChunkDao = database.videoChunkDao()
        val photoDao = database.photoDao()
        val pendingDao = database.pendingCatalogEntryDao()

        val tempFile = File(context.cacheDir, "controlled_test_video.mp4").apply {
            writeBytes("SYNTHETIC_VIDEO_BYTES_FOR_AUDIT".toByteArray())
        }

        val photo = Photo(
            localId = "video_local_001",
            pathUri = tempFile.toURI().toString(),
            photoType = "mp4",
            uploadStatus = "UPLOADING",
            mediaType = "VIDEO"
        )
        photoDao.insertPhotos(photo)

        val backup = ChunkedBackup(
            backupId = "backup_lifecycle_001",
            localId = "video_local_001",
            pathUri = tempFile.toURI().toString(),
            fileName = "controlled_test_video.mp4",
            fileSize = tempFile.length(),
            mimeType = "video/mp4",
            chunkSize = 16L * 1024 * 1024,
            totalChunks = 2,
            completedChunks = 2,
            status = ChunkedBackupStatus.VERIFYING,
            channelId = channelId,
            botId = botId,
            botUsername = "TestBot",
            fileSha256 = "video_sha256_mock_hash_1234567890"
        )
        chunkedBackupDao.insert(backup)

        val chunk0 = VideoChunk(
            backupId = "backup_lifecycle_001",
            chunkIndex = 0,
            startOffset = 0L,
            endOffset = 16L,
            chunkSize = 16L,
            chunkSha256 = "c0_hash",
            status = VideoChunkStatus.DONE,
            telegramFileId = "tg_chunk_0",
            telegramMessageId = 101L
        )
        val chunk1 = VideoChunk(
            backupId = "backup_lifecycle_001",
            chunkIndex = 1,
            startOffset = 16L,
            endOffset = tempFile.length(),
            chunkSize = tempFile.length() - 16L,
            chunkSha256 = "c1_hash",
            status = VideoChunkStatus.DONE,
            telegramFileId = "tg_chunk_1",
            telegramMessageId = 102L
        )
        videoChunkDao.insertAll(listOf(chunk0, chunk1))

        // Mock TelegramCatalogApi for manifest upload
        val mockApi = object : TelegramCatalogApi() {
            override suspend fun sendDocument(
                botToken: String,
                channelId: Long,
                fileBytes: ByteArray,
                fileName: String,
                mimeType: String,
                messageThreadId: Long?,
                caption: String?,
                baseUrl: String
            ): TelegramCatalogUploadResult {
                return TelegramCatalogUploadResult.Success(
                    fileId = "manifest_doc_file_id_001",
                    messageId = 555L,
                    fileSize = fileBytes.size.toLong(),
                    chatId = channelId
                )
            }
        }

        val manifestManager = BackupManifestManager(mockApi)
        val uploadResult = manifestManager.createAndUploadManifest(context, "backup_lifecycle_001", botToken, database)
        assertTrue(uploadResult is ManifestUploadResult.Success)

        // STAGE 1 VERIFICATION: Backup must be MANIFEST_UPLOADED, NOT DONE!
        val afterManifest = chunkedBackupDao.getById("backup_lifecycle_001")
        assertNotNull(afterManifest)
        assertEquals(
            "CRITICAL: ChunkedBackup must be in MANIFEST_UPLOADED status, NOT DONE!",
            ChunkedBackupStatus.MANIFEST_UPLOADED,
            afterManifest!!.status
        )
        assertEquals("manifest_doc_file_id_001", afterManifest.manifestFileId)
        assertEquals(555L, afterManifest.manifestMessageId)

        // Verify PendingCatalogEntry queued
        val pending = pendingDao.getPendingEntries()
        assertEquals(1, pending.size)
        assertEquals(PendingCatalogEntry.TYPE_MANIFEST_REFERENCE, pending[0].entryType)
        assertEquals(PendingCatalogEntry.STATUS_PENDING, pending[0].status)

        // STAGE 2 VERIFICATION: Simulate MasterIndexManager commit
        val mockIndexApi = object : TelegramCatalogApi() {
            override suspend fun sendDocument(
                botToken: String,
                channelId: Long,
                fileBytes: ByteArray,
                fileName: String,
                mimeType: String,
                messageThreadId: Long?,
                caption: String?,
                baseUrl: String
            ): TelegramCatalogUploadResult {
                return TelegramCatalogUploadResult.Success(
                    fileId = "master_index_file_id_v1",
                    messageId = 600L,
                    fileSize = fileBytes.size.toLong(),
                    chatId = channelId
                )
            }

            override suspend fun pinChatMessage(
                botToken: String,
                channelId: Long,
                messageId: Long,
                disableNotification: Boolean,
                baseUrl: String
            ): TelegramCatalogResult<Boolean> = TelegramCatalogResult.Success(true)

            override suspend fun getChatInfo(
                botToken: String,
                channelId: Long,
                baseUrl: String
            ): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        title = "Backup Channel",
                        type = "channel",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 600L,
                            date = 1700000000L,
                            document = TelegramDocument(
                                fileId = "master_index_file_id_v1",
                                fileUniqueId = "u_master_index",
                                fileName = MasterIndex.DEFAULT_INDEX_FILE_NAME,
                                mimeType = "application/json",
                                fileSize = 1000L
                            )
                        )
                    )
                )
            }
        }

        val indexManager = MasterIndexManager(mockIndexApi)
        val commitResult = indexManager.commitPendingEntries(context, botToken, channelId, database)
        assertTrue(commitResult is MasterIndexCommitResult.Success)

        // STAGE 3 VERIFICATION: Backup is now confirmed in Master Index and MUST be DONE!
        val afterCommit = chunkedBackupDao.getById("backup_lifecycle_001")
        assertNotNull(afterCommit)
        assertEquals(
            "ChunkedBackup must transition to DONE only after Master Index commit success",
            ChunkedBackupStatus.DONE,
            afterCommit!!.status
        )
        assertNotNull(afterCommit.completedAt)

        // Verify Photo updated to DONE
        val updatedPhoto = photoDao.getPhotoByLocalId("video_local_001")
        assertNotNull(updatedPhoto)
        assertEquals("DONE", updatedPhoto!!.uploadStatus)
        assertEquals("CHUNKED_backup_lifecycle_001", updatedPhoto.remoteId)

        // Verify pending entry marked COMMITTED
        val pendingAfter = pendingDao.getPendingEntries()
        assertTrue(pendingAfter.isEmpty())

        tempFile.delete()
    }

    @Test
    fun testCrashRecoveryReconciliation_pinnedIndexAheadOfLocalRoomState(): Unit = runBlocking {
        val chunkedBackupDao = database.chunkedBackupDao()
        val photoDao = database.photoDao()
        val pendingDao = database.pendingCatalogEntryDao()
        val catalogDao = database.backupCatalogDao()

        val manifestRef = ManifestReference(
            backupId = "backup_crash_001",
            fileId = "manifest_file_remote",
            messageId = 777L,
            fileName = "gallerysync_manifest_backup_crash_001.json",
            sizeBytes = 2000L,
            sha256 = "manifest_hash",
            mediaSha256 = "media_hash_crash",
            displayName = "crash_video.mp4"
        )
        val pendingEntry = PendingCatalogEntry(
            entryType = PendingCatalogEntry.TYPE_MANIFEST_REFERENCE,
            referenceId = "backup_crash_001",
            payloadJson = mapper.writeValueAsString(manifestRef),
            status = PendingCatalogEntry.STATUS_PENDING
        )
        pendingDao.insertOrReplace(pendingEntry)

        val backup = ChunkedBackup(
            backupId = "backup_crash_001",
            localId = "photo_crash_001",
            pathUri = "content://media/123",
            fileName = "crash_video.mp4",
            fileSize = 1000000L,
            mimeType = "video/mp4",
            totalChunks = 1,
            completedChunks = 1,
            status = ChunkedBackupStatus.MANIFEST_UPLOADED,
            channelId = channelId,
            botId = botId,
            manifestFileId = "manifest_file_remote",
            manifestMessageId = 777L
        )
        chunkedBackupDao.insert(backup)

        val photo = Photo(
            localId = "photo_crash_001",
            pathUri = "content://media/123",
            photoType = "mp4",
            uploadStatus = "UPLOADING"
        )
        photoDao.insertPhotos(photo)

        // Telegram already has Generation 5 pinned (simulating crash right after pin!)
        val remoteIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 5L,
            updatedAt = System.currentTimeMillis(),
            botId = botId,
            channelId = channelId,
            manifests = listOf(manifestRef),
            shards = emptyList(),
            activeMediaRecords = emptyList()
        )
        val remoteIndexBytes = mapper.writeValueAsBytes(remoteIndex)

        val mockApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(
                botToken: String,
                channelId: Long,
                baseUrl: String
            ): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        title = "Channel",
                        type = "channel",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 888L,
                            date = 1700000000L,
                            document = TelegramDocument(
                                fileId = "doc_gen_5_file_id",
                                fileUniqueId = "u_doc_gen_5",
                                fileName = MasterIndex.DEFAULT_INDEX_FILE_NAME,
                                mimeType = "application/json",
                                fileSize = remoteIndexBytes.size.toLong()
                            )
                        )
                    )
                )
            }

            override suspend fun downloadFileByFileId(
                botToken: String,
                fileId: String,
                maxSizeBytes: Long,
                baseUrl: String
            ): TelegramCatalogResult<ByteArray> {
                return TelegramCatalogResult.Success(remoteIndexBytes)
            }
        }

        val indexManager = MasterIndexManager(mockApi)

        // Execute startup reconciliation
        val reconResult = indexManager.reconcileWithRemoteCatalog(botToken, channelId, database)
        assertTrue("Reconciliation must succeed", reconResult is MasterIndexReconciliationResult.Reconciled)
        assertEquals(5L, (reconResult as MasterIndexReconciliationResult.Reconciled).generation)

        // Verify local Room catalog advanced to Gen 5
        val localCatalog = catalogDao.getByChannelId(channelId)
        assertNotNull(localCatalog)
        assertEquals(5L, localCatalog!!.lastConfirmedGeneration)
        assertEquals(888L, localCatalog.pinnedMessageId)

        // Verify ChunkedBackup marked DONE
        val recoveredBackup = chunkedBackupDao.getById("backup_crash_001")
        assertNotNull(recoveredBackup)
        assertEquals(ChunkedBackupStatus.DONE, recoveredBackup!!.status)

        // Verify Photo marked DONE
        val recoveredPhoto = photoDao.getPhotoByLocalId("photo_crash_001")
        assertNotNull(recoveredPhoto)
        assertEquals("DONE", recoveredPhoto!!.uploadStatus)
        assertEquals("CHUNKED_backup_crash_001", recoveredPhoto.remoteId)

        // Verify pending entry marked COMMITTED
        val remainingPending = pendingDao.getPendingEntries()
        assertTrue("Pending entry should be marked COMMITTED", remainingPending.isEmpty())
    }

    @Test
    fun testReinstallationRecovery_unmatchedMediaRemainsHoldingAndReconciledMatches(): Unit = runBlocking {
        val freshDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val photoDao = freshDb.photoDao()

        // 1. Local MediaStore has scanned 2 items: 1 backed up previously in Telegram, 1 genuinely new
        val matchedPhoto = Photo(
            localId = "existing_backed_up_local",
            pathUri = "content://media/photos/101",
            photoType = "jpg",
            uploadStatus = "HOLDING",
            contentHash = "exact_sha256_hash_photo_101"
        )
        val newPhoto = Photo(
            localId = "genuinely_new_local",
            pathUri = "content://media/photos/102",
            photoType = "jpg",
            uploadStatus = "HOLDING",
            contentHash = "new_photo_hash_different"
        )
        photoDao.insertPhotos(matchedPhoto, newPhoto)

        // 2. Telegram Master Index contains record for photo 101
        val remoteRecord = CatalogMediaRecord(
            mediaSha256 = "exact_sha256_hash_photo_101",
            displayName = "backed_up_photo.jpg",
            sizeBytes = 2048L,
            mediaType = "IMAGE",
            telegramFileId = "tg_file_photo_101",
            telegramMessageId = 4001L,
            backupTimestamp = 1650000000000L,
            backupStatus = "DONE"
        )

        val masterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 10L,
            updatedAt = System.currentTimeMillis(),
            botId = botId,
            channelId = channelId,
            manifests = emptyList(),
            shards = emptyList(),
            activeMediaRecords = listOf(remoteRecord)
        )
        val indexBytes = mapper.writeValueAsBytes(masterIndex)

        val mockApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(
                botToken: String,
                channelId: Long,
                baseUrl: String
            ): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        title = "Backup Channel",
                        type = "channel",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 9999L,
                            date = 1700000000L,
                            document = TelegramDocument(
                                fileId = "master_index_file_id_gen10",
                                fileUniqueId = "u_master_gen10",
                                fileName = MasterIndex.DEFAULT_INDEX_FILE_NAME,
                                mimeType = "application/json",
                                fileSize = indexBytes.size.toLong()
                            )
                        )
                    )
                )
            }

            override suspend fun downloadFileByFileId(
                botToken: String,
                fileId: String,
                maxSizeBytes: Long,
                baseUrl: String
            ): TelegramCatalogResult<ByteArray> {
                return TelegramCatalogResult.Success(indexBytes)
            }
        }

        val indexManager = MasterIndexManager(mockApi)

        // Recover catalog from Telegram
        val recoveryResult = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = freshDb,
            catalogApi = mockApi,
            indexManager = indexManager
        )
        assertTrue("Recovery must succeed", recoveryResult is TelegramCatalogRecoveryResult.Success)
        val success = recoveryResult as TelegramCatalogRecoveryResult.Success
        assertEquals(1, success.mediaRecordsRecovered)

        // Verify recovery automatically reconciled local photos with recovered remote history
        assertEquals("Exactly 1 photo must be matched via SHA-256", 1, success.localReconciledCount)

        // Verify matched photo transitioned to DONE
        val matchedInDb = photoDao.getPhotoByLocalId("existing_backed_up_local")
        assertNotNull(matchedInDb)
        assertEquals("DONE", matchedInDb!!.uploadStatus)
        assertEquals("tg_file_photo_101", matchedInDb.remoteId)

        // CRITICAL REINSTALL PROTECTION: Unmatched photo MUST remain in HOLDING!
        val unmatchedInDb = photoDao.getPhotoByLocalId("genuinely_new_local")
        assertNotNull(unmatchedInDb)
        assertEquals(
            "CRITICAL: Unmatched existing media must remain in HOLDING state!",
            "HOLDING",
            unmatchedInDb!!.uploadStatus
        )

        freshDb.close()
    }

    @Test
    fun testReinstallationRecovery_channelOrBotMismatch_isRejected(): Unit = runBlocking {
        val freshDb = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val masterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 1L,
            botId = 999999999L, // Wrong bot ID!
            channelId = channelId
        )
        val indexBytes = mapper.writeValueAsBytes(masterIndex)

        val mockApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(
                botToken: String,
                channelId: Long,
                baseUrl: String
            ): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        title = "Backup Channel",
                        type = "channel",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 1111L,
                            date = 1700000000L,
                            document = TelegramDocument(
                                fileId = "wrong_index",
                                fileUniqueId = "u_wrong",
                                fileName = MasterIndex.DEFAULT_INDEX_FILE_NAME,
                                mimeType = "application/json",
                                fileSize = indexBytes.size.toLong()
                            )
                        )
                    )
                )
            }

            override suspend fun downloadFileByFileId(
                botToken: String,
                fileId: String,
                maxSizeBytes: Long,
                baseUrl: String
            ): TelegramCatalogResult<ByteArray> {
                return TelegramCatalogResult.Success(indexBytes)
            }
        }

        val indexManager = MasterIndexManager(mockApi)

        val result = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = freshDb,
            catalogApi = mockApi,
            indexManager = indexManager
        )
        assertTrue("Must reject mismatched bot/channel identities", result is TelegramCatalogRecoveryResult.IdentityMismatch)

        freshDb.close()
    }
}
