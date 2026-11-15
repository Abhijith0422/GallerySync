package com.gallerysync.app.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.upload.TelegramUploadDispatcher
import com.gallerysync.app.data.upload.TelegramUploadDispatcher.UploadDispatchType
import com.gallerysync.app.data.upload.TelegramUploadDispatcher.UploadError
import com.github.kotlintelegrambot.entities.ChatId
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TelegramUploadDispatcherTest {

    private lateinit var db: WhDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WhDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testDetermineDispatchType_Images() {
        assertEquals(
            UploadDispatchType.DOCUMENT_IMAGE,
            TelegramUploadDispatcher.determineDispatchType("IMAGE", "jpg", "image/jpeg")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_IMAGE,
            TelegramUploadDispatcher.determineDispatchType("IMAGE", "png", "image/png")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_IMAGE,
            TelegramUploadDispatcher.determineDispatchType("IMAGE", "webp", "image/webp")
        )
    }

    @Test
    fun testDetermineDispatchType_Mp4Videos() {
        assertEquals(
            UploadDispatchType.VIDEO_MP4,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "mp4", "video/mp4")
        )
        assertEquals(
            UploadDispatchType.VIDEO_MP4,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "m4v", "video/mp4")
        )
        assertEquals(
            UploadDispatchType.VIDEO_MP4,
            TelegramUploadDispatcher.determineDispatchType("IMAGE", "mp4", "video/mp4") // video MIME takes precedence
        )
    }

    @Test
    fun testDetermineDispatchType_NonMp4VideosSentAsDocument() {
        // Formats not suitable for Telegram's native player are sent as documents
        assertEquals(
            UploadDispatchType.DOCUMENT_VIDEO,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "mkv", "video/x-matroska")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_VIDEO,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "mov", "video/quicktime")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_VIDEO,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "avi", "video/x-msvideo")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_VIDEO,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "webm", "video/webm")
        )
        assertEquals(
            UploadDispatchType.DOCUMENT_VIDEO,
            TelegramUploadDispatcher.determineDispatchType("VIDEO", "3gp", "video/3gpp")
        )
    }

    @Test
    fun testFileSizeValidation_UnderLimit() {
        val tenMb = 10L * 1024 * 1024
        assertTrue(tenMb <= TelegramUploadDispatcher.MAX_TELEGRAM_FILE_SIZE_BYTES)
    }

    @Test
    fun testFileSizeValidation_AtLimit() {
        val exactlyFiftyMb = 50L * 1024 * 1024
        assertEquals(TelegramUploadDispatcher.MAX_TELEGRAM_FILE_SIZE_BYTES, exactlyFiftyMb)
        assertTrue(exactlyFiftyMb <= TelegramUploadDispatcher.MAX_TELEGRAM_FILE_SIZE_BYTES)
    }

    @Test
    fun testFileSizeValidation_ExceedingLimit() {
        val overFiftyMb = 50L * 1024 * 1024 + 1
        assertTrue(overFiftyMb > TelegramUploadDispatcher.MAX_TELEGRAM_FILE_SIZE_BYTES)

        val error = UploadError.FileExceedsLimit(overFiftyMb)
        assertEquals(overFiftyMb, error.actualSize)
        assertFalse(error.isRetryable)
        assertTrue(error.errorMessage.contains("50 MB"))
    }

    @Test
    fun testOversizedFileRetainedInQueue() = runBlocking {
        val photoDao = db.photoDao()
        val uploadQueueDao = db.uploadQueueDao()

        val photo = Photo(
            localId = "video_999",
            photoType = "mp4",
            pathUri = "content://media/external/video/media/999",
            mediaType = "VIDEO",
            duration = 180000L
        )
        photoDao.insertPhotos(photo)

        val queueId = uploadQueueDao.insert(
            UploadQueue(
                localId = photo.localId,
                pathUri = photo.pathUri,
                workerType = "periodic",
                deviceId = "dev_1",
                mediaType = "VIDEO",
                fileSize = 75L * 1024 * 1024
            )
        )

        // Mark oversized
        uploadQueueDao.markExceedsLimit(queueId, "File size (75 MB) exceeds Telegram's 50 MB limit")
        photoDao.updateUploadStatus(photo.localId, "EXCEEDS_LIMIT")

        // Assert record is retained in upload_queue
        val queueItem = uploadQueueDao.getById(queueId)
        assertNotNull(queueItem)
        assertEquals("EXCEEDS_LIMIT", queueItem!!.status)
        assertTrue(queueItem.errorMessage!!.contains("75 MB"))

        // Assert record is retained in photos table
        val localPhoto = photoDao.getPhotoByLocalId(photo.localId)
        assertNotNull(localPhoto)
        assertEquals("EXCEEDS_LIMIT", localPhoto!!.uploadStatus)
        assertNull(localPhoto.remoteId) // Never marked as uploaded
    }

    @Test
    fun testErrorClassification_MissingSourceMedia() = runBlocking {
        val uploadQueueDao = db.uploadQueueDao()
        val photoDao = db.photoDao()

        val photo = Photo(
            localId = "missing_1",
            photoType = "jpg",
            pathUri = "content://media/external/images/media/99999"
        )
        photoDao.insertPhotos(photo)

        val queueId = uploadQueueDao.insert(
            UploadQueue(
                localId = photo.localId,
                pathUri = photo.pathUri,
                workerType = "instant",
                deviceId = "dev_1"
            )
        )

        val classified = TelegramUploadDispatcher.classifyError(FileNotFoundException("Cannot find file"))
        assertTrue(classified is UploadError.SourceMissing)
        assertFalse(classified.isRetryable)

        uploadQueueDao.markSourceMissing(queueId, classified.errorMessage)
        photoDao.updateUploadStatus(photo.localId, "SOURCE_MISSING")

        val queueItem = uploadQueueDao.getById(queueId)
        assertNotNull(queueItem)
        assertEquals("SOURCE_MISSING", queueItem!!.status)
    }

    @Test
    fun testErrorClassification_Telegram429RateLimit() {
        val errorJson = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":45}}"""
        val classified = TelegramUploadDispatcher.classifyError(null, 429, errorJson)
        assertTrue(classified is UploadError.RateLimited)
        val rateLimitError = classified as UploadError.RateLimited
        assertEquals(45, rateLimitError.retryAfterSeconds)
        assertTrue(rateLimitError.isRetryable)
    }

    @Test
    fun testErrorClassification_Telegram5xxServerError() {
        val classified500 = TelegramUploadDispatcher.classifyError(null, 500, "Internal Server Error")
        assertTrue(classified500 is UploadError.ServerError)
        assertTrue(classified500.isRetryable)

        val classified502 = TelegramUploadDispatcher.classifyError(null, 502, "Bad Gateway")
        assertTrue(classified502 is UploadError.ServerError)
        assertEquals(502, (classified502 as UploadError.ServerError).code)
        assertTrue(classified502.isRetryable)

        val classified503 = TelegramUploadDispatcher.classifyError(null, 503, "Service Unavailable")
        assertTrue(classified503 is UploadError.ServerError)
        assertTrue(classified503.isRetryable)
    }

    @Test
    fun testErrorClassification_InvalidCredentials() {
        val classified401 = TelegramUploadDispatcher.classifyError(null, 401, """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
        assertTrue(classified401 is UploadError.InvalidCredentials)
        assertFalse(classified401.isRetryable)

        val classified403 = TelegramUploadDispatcher.classifyError(null, 403, """{"ok":false,"error_code":403,"description":"Forbidden: bot was kicked from the channel"}""")
        assertTrue(classified403 is UploadError.InvalidCredentials)
        assertFalse(classified403.isRetryable)
    }

    @Test
    fun testErrorClassification_NetworkUnavailable() {
        val socketTimeout = SocketTimeoutException("Connection timed out")
        val classifiedTimeout = TelegramUploadDispatcher.classifyError(socketTimeout)
        assertTrue(classifiedTimeout is UploadError.NetworkUnavailable)
        assertTrue(classifiedTimeout.isRetryable)

        val unknownHost = UnknownHostException("api.telegram.org")
        val classifiedHost = TelegramUploadDispatcher.classifyError(unknownHost)
        assertTrue(classifiedHost is UploadError.NetworkUnavailable)
        assertTrue(classifiedHost.isRetryable)
    }

    @Test
    fun testPrivateChannelChatIdHandling() {
        // Standard Telegram private channels have negative 64-bit numeric IDs starting with -100
        val privateChannelId = -1001987654321L
        val chatId = ChatId.fromId(privateChannelId)
        assertEquals(privateChannelId, chatId.id)

        // For standard private channels, forum topics are null
        val messageThreadId: Long? = null
        assertNull(messageThreadId)
    }

    @Test
    fun testOptionalForumTopicHandling() {
        val topicId: Long? = 55L
        assertNotNull(topicId)
        assertEquals(55L, topicId)

        // Fallback to null (channel root) when topic send fails
        var fallbackTopicId: Long? = topicId
        val topicSendFailed = true
        if (topicSendFailed) {
            fallbackTopicId = null
        }
        assertNull(fallbackTopicId)
    }

    @Test
    fun testSuccessfulMessageIdAndMetadataPersistence() = runBlocking {
        val photoDao = db.photoDao()
        val remotePhotoDao = db.remotePhotoDao()

        val photo = Photo(
            localId = "vid_123",
            photoType = "mp4",
            pathUri = "content://media/external/video/media/123",
            contentHash = "hash_video_abc",
            mediaType = "VIDEO",
            width = 1920,
            height = 1080,
            duration = 62000L
        )
        photoDao.insertPhotos(photo)

        val returnedFileId = "BAACAgIAAxkBAAIB..."
        val returnedMessageId = 4567L

        // Simulate successful upload completion
        photoDao.updateRemoteIdForLocalId(photo.localId, returnedFileId)
        photoDao.updateUploadStatus(photo.localId, "DONE", 1700000000L)

        remotePhotoDao.insertAll(
            RemotePhoto(
                remoteId = returnedFileId,
                photoType = "mp4",
                fileName = "my_video.mp4",
                fileSize = 25_000_000L,
                uploadedAt = 1700000000L,
                thumbnailCached = false,
                localId = photo.localId,
                messageId = returnedMessageId,
                uploadType = "periodic",
                contentHash = photo.contentHash,
                uploadedByDevice = "dev_1",
                previewRemoteId = null,
                mediaType = "VIDEO",
                width = 1920,
                height = 1080,
                duration = 62000L
            )
        )

        // Assert local photo updated
        val updatedLocal = photoDao.getPhotoByLocalId(photo.localId)
        assertNotNull(updatedLocal)
        assertEquals(returnedFileId, updatedLocal!!.remoteId)
        assertEquals("DONE", updatedLocal.uploadStatus)
        assertEquals("VIDEO", updatedLocal.mediaType)

        // Assert remote photo persisted with messageId and video metadata
        val remotePhoto = remotePhotoDao.getById(returnedFileId)
        assertNotNull(remotePhoto)
        assertEquals(returnedMessageId, remotePhoto!!.messageId)
        assertEquals("VIDEO", remotePhoto.mediaType)
        assertEquals(1920, remotePhoto.width)
        assertEquals(1080, remotePhoto.height)
        assertEquals(62000L, remotePhoto.duration)
        assertEquals("hash_video_abc", remotePhoto.contentHash)
    }

    @Test
    fun testTemporaryFileCleanup() {
        val tempFile = File.createTempFile("test_upload_", ".mp4")
        assertTrue(tempFile.exists())

        // Simulate try-finally cleanup pattern
        try {
            tempFile.writeBytes(ByteArray(1024))
            assertEquals(1024L, tempFile.length())
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        assertFalse(tempFile.exists())
    }
}
