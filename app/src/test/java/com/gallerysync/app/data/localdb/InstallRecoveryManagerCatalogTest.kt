package com.gallerysync.app.data.localdb

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.gallerysync.app.api.TelegramCatalogApi
import com.gallerysync.app.api.TelegramCatalogChatInfo
import com.gallerysync.app.api.TelegramCatalogResult
import com.gallerysync.app.api.TelegramDocument
import com.gallerysync.app.api.TelegramPinnedMessage
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
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallRecoveryManagerCatalogTest {

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
        DbHolder.database = database
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRecoveryFromTelegram_reconstructsRemotePhotosAndChunkedBackups() = runBlocking {
        val photoDao = database.photoDao()
        val chunkedDao = database.chunkedBackupDao()
        val videoChunkDao = database.videoChunkDao()
        val catalogDao = database.backupCatalogDao()

        val photoHashMatched = "photo_hash_matched_1234567890abcdef1234567890abcdef1234567890"
        val videoHashMatched = "video_hash_matched_1234567890abcdef1234567890abcdef1234567890"
        val unbackedUpHash = "unbacked_up_local_hash_0000000000000000000000000000000000000000"

        // Insert unverified local media in HOLDING state (simulating post-reinstall scan)
        photoDao.insertPhotos(
            Photo(
                localId = "local_p1",
                remoteId = null,
                photoType = "jpg",
                pathUri = "content://media/external/images/media/1",
                contentHash = photoHashMatched,
                uploadStatus = Photo.STATUS_HOLDING,
                uploadRetryCount = 0
            ),
            Photo(
                localId = "local_v1",
                remoteId = null,
                photoType = "mp4",
                pathUri = "content://media/external/video/media/2",
                contentHash = videoHashMatched,
                uploadStatus = Photo.STATUS_HOLDING,
                uploadRetryCount = 0,
                mediaType = "VIDEO"
            ),
            Photo(
                localId = "local_unrelated",
                remoteId = null,
                photoType = "jpg",
                pathUri = "content://media/external/images/media/3",
                contentHash = unbackedUpHash,
                uploadStatus = Photo.STATUS_HOLDING,
                uploadRetryCount = 0
            )
        )

        // Build mock Manifest for chunked video
        val manifest = BackupManifest(
            format = BackupManifest.FORMAT_IDENTIFIER,
            version = BackupManifest.CURRENT_VERSION,
            backupId = "bk_video_100",
            createdAt = 1700000000000L,
            application = ApplicationInfo(),
            bot = BotIdentity(id = botId),
            destination = DestinationInfo(chatId = channelId),
            media = MediaDescriptor(
                originalName = "my_holiday.mp4",
                mimeType = "video/mp4",
                sizeBytes = 32L * 1024 * 1024,
                sha256 = videoHashMatched
            ),
            chunkConfiguration = ManifestChunkConfig(16L * 1024 * 1024, 2),
            chunks = listOf(
                ManifestChunkDescriptor(0, 0, 16L * 1024 * 1024, 16L * 1024 * 1024, "chunk0_hash", "tg_file_chunk_0", 101L),
                ManifestChunkDescriptor(1, 16L * 1024 * 1024, 32L * 1024 * 1024, 16L * 1024 * 1024, "chunk1_hash", "tg_file_chunk_1", 102L)
            )
        )
        val manifestBytes = mapper.writeValueAsBytes(manifest)

        // Build mock MasterIndex
        val manifestRef = ManifestReference(
            backupId = "bk_video_100",
            fileId = "tg_manifest_doc_id",
            messageId = 500L,
            fileName = "gallerysync_manifest_bk_video_100.json",
            sizeBytes = manifestBytes.size.toLong(),
            sha256 = "manifest_sha256",
            mediaSha256 = videoHashMatched,
            displayName = "my_holiday.mp4"
        )
        val mediaRecord = CatalogMediaRecord(
            mediaSha256 = photoHashMatched,
            displayName = "family_photo.jpg",
            sizeBytes = 2L * 1024 * 1024,
            mediaType = "IMAGE",
            telegramFileId = "tg_photo_doc_id",
            telegramMessageId = 400L,
            backupTimestamp = 1700000000000L,
            backupStatus = "DONE",
            localId = "local_p1"
        )

        val masterIndex = MasterIndex(
            format = MasterIndex.FORMAT_IDENTIFIER,
            version = MasterIndex.CURRENT_VERSION,
            generation = 5L,
            updatedAt = System.currentTimeMillis(),
            botId = botId,
            channelId = channelId,
            manifests = listOf(manifestRef),
            shards = emptyList(),
            activeMediaRecords = listOf(mediaRecord)
        )
        val indexBytes = mapper.writeValueAsBytes(masterIndex)

        // Custom TelegramCatalogApi subclass for testing
        val fakeCatalogApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(botToken: String, channelId: Long, baseUrl: String): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        type = "channel",
                        title = "Test Channel",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 999L,
                            date = 1700000000L,
                            document = TelegramDocument(
                                fileId = "tg_index_doc_id",
                                fileUniqueId = "u_index",
                                fileName = "gallerysync_master_index.json",
                                mimeType = "application/json",
                                fileSize = indexBytes.size.toLong()
                            )
                        )
                    )
                )
            }

            override suspend fun downloadFileByFileId(botToken: String, fileId: String, maxSizeBytes: Long, baseUrl: String): TelegramCatalogResult<ByteArray> {
                return when (fileId) {
                    "tg_index_doc_id" -> TelegramCatalogResult.Success(indexBytes)
                    "tg_manifest_doc_id" -> TelegramCatalogResult.Success(manifestBytes)
                    else -> TelegramCatalogResult.InvalidResponse("File not found: $fileId")
                }
            }
        }

        val fakeIndexManager = MasterIndexManager(fakeCatalogApi)

        // Perform recovery
        val recoveryResult = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = database,
            catalogApi = fakeCatalogApi,
            indexManager = fakeIndexManager
        )

        assertTrue("Recovery must succeed", recoveryResult is TelegramCatalogRecoveryResult.Success)
        val success = recoveryResult as TelegramCatalogRecoveryResult.Success
        assertEquals(5L, success.generation)
        assertEquals(1, success.manifestsRecovered)
        assertEquals(1, success.mediaRecordsRecovered)
        assertEquals(2, success.localReconciledCount) // photo and video matched!

        // 1. Verify ChunkedBackup was reconstructed in Room
        val cb = chunkedDao.getById("bk_video_100")
        assertNotNull(cb)
        assertEquals(ChunkedBackupStatus.DONE, cb!!.status)
        assertEquals("tg_manifest_doc_id", cb.manifestFileId)
        val chunks = videoChunkDao.getByBackupId("bk_video_100")
        assertEquals(2, chunks.size)
        assertEquals("tg_file_chunk_0", chunks[0].telegramFileId)
        assertEquals("tg_file_chunk_1", chunks[1].telegramFileId)

        // 2. Verify local media states
        val localPhoto = photoDao.getPhotoByLocalId("local_p1")
        assertNotNull(localPhoto)
        assertEquals(Photo.STATUS_DONE, localPhoto!!.uploadStatus)
        assertEquals("tg_photo_doc_id", localPhoto.remoteId)

        val localVideo = photoDao.getPhotoByLocalId("local_v1")
        assertNotNull(localVideo)
        assertEquals(Photo.STATUS_DONE, localVideo!!.uploadStatus)
        assertEquals("tg_manifest_doc_id", localVideo.remoteId)

        // 3. Verify unmatched local media remains strictly in HOLDING state
        val unmatched = photoDao.getPhotoByLocalId("local_unrelated")
        assertNotNull(unmatched)
        assertEquals(Photo.STATUS_HOLDING, unmatched!!.uploadStatus)
        assertEquals(null, unmatched.remoteId)

        // 4. Verify catalog entity was recorded
        val catalog = catalogDao.getByChannelId(channelId)
        assertNotNull(catalog)
        assertEquals(5L, catalog!!.lastConfirmedGeneration)
        assertEquals(999L, catalog.pinnedMessageId)
        assertEquals("tg_index_doc_id", catalog.indexFileId)

        // 5. Verify baseline is completed
        assertTrue(InstallRecoveryManager.isBaselineCompleted(database))
    }

    @Test
    fun testRecoveryFromTelegram_whenIdentityMismatch_failsWithoutModifyingDatabase() = runBlocking {
        val masterIndex = MasterIndex(
            generation = 1L,
            botId = 999999999L, // different botId
            channelId = channelId
        )
        val indexBytes = mapper.writeValueAsBytes(masterIndex)

        val fakeCatalogApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(botToken: String, channelId: Long, baseUrl: String): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        type = "channel",
                        title = "Test",
                        pinnedMessage = TelegramPinnedMessage(
                            messageId = 1L,
                            date = 100L,
                            document = TelegramDocument("doc_id", null, "gallerysync_master_index.json", "application/json", 100L)
                        )
                    )
                )
            }

            override suspend fun downloadFileByFileId(botToken: String, fileId: String, maxSizeBytes: Long, baseUrl: String): TelegramCatalogResult<ByteArray> {
                return TelegramCatalogResult.Success(indexBytes)
            }
        }

        val result = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = database,
            catalogApi = fakeCatalogApi
        )

        assertTrue(result is TelegramCatalogRecoveryResult.IdentityMismatch)
    }

    @Test
    fun testRecoveryFromTelegram_whenNoPinnedMessage_returnsNoCatalogFound() = runBlocking {
        val fakeCatalogApi = object : TelegramCatalogApi() {
            override suspend fun getChatInfo(botToken: String, channelId: Long, baseUrl: String): TelegramCatalogResult<TelegramCatalogChatInfo> {
                return TelegramCatalogResult.Success(
                    TelegramCatalogChatInfo(
                        id = channelId,
                        type = "channel",
                        title = "Test",
                        pinnedMessage = null
                    )
                )
            }
        }

        val result = InstallRecoveryManager.recoverCatalogFromTelegram(
            context = context,
            botToken = botToken,
            channelId = channelId,
            database = database,
            catalogApi = fakeCatalogApi
        )

        assertTrue(result is TelegramCatalogRecoveryResult.NoCatalogFound)
    }
}
