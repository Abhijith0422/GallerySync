package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import com.gallerysync.app.api.BotApi
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.upload.TelegramUploadDispatcher
import com.gallerysync.app.utils.coil.NetworkFetcher
import com.github.kotlintelegrambot.entities.Message
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class Phase9TelegramThumbnailPersistenceTest {

    private val gson = Gson()
    private lateinit var context: Context
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    fun testVideoResponseWithThumbnail_ExtractsThumbnailAndPreservesRemoteId() {
        val json = """
            {
                "message_id": 1001,
                "date": 1600000000,
                "chat": {"id": -100123456, "type": "channel", "title": "Test Channel"},
                "video": {
                    "file_id": "video_file_id_original",
                    "file_unique_id": "unique_video_1",
                    "width": 1920,
                    "height": 1080,
                    "duration": 45,
                    "thumb": {
                        "file_id": "video_thumb_file_id_123",
                        "file_unique_id": "unique_thumb_1",
                        "width": 320,
                        "height": 180,
                        "file_size": 12345
                    }
                }
            }
        """.trimIndent()

        val message = gson.fromJson(json, Message::class.java)
        assertNotNull(message)

        val previewId = TelegramUploadDispatcher.extractPreviewRemoteId(message)
        assertEquals("video_thumb_file_id_123", previewId)

        // Remote ID extraction
        assertEquals("video_file_id_original", message.video?.fileId)
    }

    @Test
    fun testDocumentResponseWithThumbnail_ExtractsThumbnailAndPreservesRemoteId() {
        val json = """
            {
                "message_id": 1002,
                "date": 1600000000,
                "chat": {"id": -100123456, "type": "channel", "title": "Test Channel"},
                "document": {
                    "file_id": "doc_video_file_id_original",
                    "file_unique_id": "unique_doc_1",
                    "file_name": "clip.mkv",
                    "mime_type": "video/x-matroska",
                    "file_size": 35000000,
                    "thumb": {
                        "file_id": "doc_thumb_file_id_456",
                        "file_unique_id": "unique_doc_thumb_1",
                        "width": 320,
                        "height": 240,
                        "file_size": 8900
                    }
                }
            }
        """.trimIndent()

        val message = gson.fromJson(json, Message::class.java)
        assertNotNull(message)

        val previewId = TelegramUploadDispatcher.extractPreviewRemoteId(message)
        assertEquals("doc_thumb_file_id_456", previewId)
        assertEquals("doc_video_file_id_original", message.document?.fileId)
    }

    @Test
    fun testVideoResponseWithoutThumbnail_ReturnsNullPreviewIdAndPreservesRemoteId() {
        val json = """
            {
                "message_id": 1003,
                "date": 1600000000,
                "chat": {"id": -100123456, "type": "channel", "title": "Test Channel"},
                "video": {
                    "file_id": "video_no_thumb_id",
                    "file_unique_id": "unique_video_no_thumb",
                    "width": 1280,
                    "height": 720,
                    "duration": 15
                }
            }
        """.trimIndent()

        val message = gson.fromJson(json, Message::class.java)
        assertNotNull(message)

        val previewId = TelegramUploadDispatcher.extractPreviewRemoteId(message)
        assertNull("Preview remote ID must be null when Telegram sends no thumb", previewId)
        assertEquals("video_no_thumb_id", message.video?.fileId)
    }

    @Test
    fun testDocumentResponseWithoutThumbnail_ReturnsNullPreviewIdAndPreservesRemoteId() {
        val json = """
            {
                "message_id": 1004,
                "date": 1600000000,
                "chat": {"id": -100123456, "type": "channel", "title": "Test Channel"},
                "document": {
                    "file_id": "doc_no_thumb_id",
                    "file_unique_id": "unique_doc_no_thumb",
                    "file_name": "raw_video.avi"
                }
            }
        """.trimIndent()

        val message = gson.fromJson(json, Message::class.java)
        assertNotNull(message)

        val previewId = TelegramUploadDispatcher.extractPreviewRemoteId(message)
        assertNull("Preview remote ID must be null when Telegram sends no thumb on document", previewId)
        assertEquals("doc_no_thumb_id", message.document?.fileId)
    }

    @Test
    fun testNetworkFetcher_VideoWithThumbnail_FetchesPreviewRemoteId() = runBlocking {
        var requestedFileId: String? = null

        val remotePhoto = RemotePhoto(
            remoteId = "original_heavy_video_id_45mb",
            previewRemoteId = "extracted_thumb_id_10kb",
            photoType = "mp4",
            mediaType = "VIDEO"
        )

        val fetcher = NetworkFetcher(
            remotePhoto = remotePhoto,
            options = Options(context),
            fileFetcher = { fileId ->
                requestedFileId = fileId
                "fake_jpg_bytes".toByteArray()
            }
        )

        val result = fetcher.fetch()
        assertNotNull("Fetch should succeed when thumbnail bytes are returned", result)
        assertEquals(
            "NetworkFetcher must request previewRemoteId for video, NOT the heavy video remoteId",
            "extracted_thumb_id_10kb",
            requestedFileId
        )
    }

    @Test
    fun testNetworkFetcher_VideoWithoutThumbnail_DoesNotFetchOriginalVideo() = runBlocking {
        var requestedFileId: String? = null

        val remotePhoto = RemotePhoto(
            remoteId = "huge_video_no_thumb_file_id",
            previewRemoteId = null,
            photoType = "mp4",
            mediaType = "VIDEO"
        )

        val fetcher = NetworkFetcher(
            remotePhoto = remotePhoto,
            options = Options(context),
            fileFetcher = { fileId ->
                requestedFileId = fileId
                "fake_bytes".toByteArray()
            }
        )

        val result = fetcher.fetch()
        assertNull("Fetch must return null when video has no previewRemoteId", result)
        assertNull(
            "NetworkFetcher must NEVER download full video when previewRemoteId is null",
            requestedFileId
        )
    }

    @Test
    fun testNetworkFetcher_ThumbnailDownloadFailure_DoesNotDeleteDatabaseRecord() = runBlocking {
        val remotePhotoDao = database.remotePhotoDao()

        val remotePhoto = RemotePhoto(
            remoteId = "video_network_fail_remote_id",
            previewRemoteId = "thumb_fail_id",
            photoType = "mp4",
            fileName = "my_video.mp4",
            mediaType = "VIDEO",
            status = "ACTIVE"
        )
        remotePhotoDao.insertAll(remotePhoto)

        val fetcher = NetworkFetcher(
            remotePhoto = remotePhoto,
            options = Options(context),
            fileFetcher = { null } // Simulate network failure returning null
        )

        val result = fetcher.fetch()
        assertNull("Failed download should yield null result", result)

        // Database record MUST remain intact
        val persisted = remotePhotoDao.getById("video_network_fail_remote_id")
        assertNotNull("RemotePhoto record must not be deleted when thumbnail download fails", persisted)
        assertEquals("my_video.mp4", persisted?.fileName)
        assertEquals("ACTIVE", persisted?.status)
    }

    @Test
    fun testNetworkFetcher_ImageThumbnail_FetchesNormally() = runBlocking {
        var requestedFileId: String? = null

        val remotePhoto = RemotePhoto(
            remoteId = "normal_photo_file_id",
            previewRemoteId = null,
            photoType = "jpg",
            mediaType = "IMAGE"
        )

        val fetcher = NetworkFetcher(
            remotePhoto = remotePhoto,
            options = Options(context),
            fileFetcher = { fileId ->
                requestedFileId = fileId
                "fake_image_bytes".toByteArray()
            }
        )

        val result = fetcher.fetch()
        assertNotNull("Image fetch should succeed", result)
        assertEquals(
            "For normal photos, NetworkFetcher should fetch remoteId if previewRemoteId is null",
            "normal_photo_file_id",
            requestedFileId
        )
    }
}
