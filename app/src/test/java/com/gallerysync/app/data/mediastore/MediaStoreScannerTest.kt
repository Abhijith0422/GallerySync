package com.gallerysync.app.data.mediastore

import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.utils.MediaAccessState
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaStoreScannerTest {

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
    fun testBuildSelection_FullAccess() {
        val (selection, args) = MediaStoreScanner.buildSelection(MediaAccessState.FullAccess)
        assertNotNull(selection)
        assertNotNull(args)
        assertTrue(selection!!.contains(MediaStore.Files.FileColumns.MEDIA_TYPE))
        assertEquals(2, args!!.size)
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), args[0])
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), args[1])
    }

    @Test
    fun testBuildSelection_ImagesOnlyAccess() {
        val (selection, args) = MediaStoreScanner.buildSelection(MediaAccessState.ImagesOnlyAccess)
        assertNotNull(selection)
        assertNotNull(args)
        assertEquals(1, args!!.size)
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), args[0])
    }

    @Test
    fun testBuildSelection_VideosOnlyAccess() {
        val (selection, args) = MediaStoreScanner.buildSelection(MediaAccessState.VideosOnlyAccess)
        assertNotNull(selection)
        assertNotNull(args)
        assertEquals(1, args!!.size)
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), args[0])
    }

    @Test
    fun testBuildSelection_PartialAccess() {
        val (selection, args) = MediaStoreScanner.buildSelection(MediaAccessState.PartialAccess)
        assertNotNull(selection)
        assertNotNull(args)
        assertEquals(2, args!!.size)
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), args[0])
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), args[1])
    }

    @Test
    fun testBuildSelection_NoAccess() {
        val (selection, args) = MediaStoreScanner.buildSelection(MediaAccessState.NoAccess)
        assertNull(selection)
        assertNull(args)
    }

    @Test
    fun testBuildSelection_WithSinceTimestamp() {
        val (selection, args) = MediaStoreScanner.buildSelection(
            MediaAccessState.FullAccess,
            sinceTimestampSeconds = 1700000000L
        )
        assertNotNull(selection)
        assertNotNull(args)
        assertTrue(selection!!.contains(MediaStore.Files.FileColumns.DATE_MODIFIED))
        assertEquals(3, args!!.size)
        assertEquals("1700000000", args[2])
    }

    @Test
    fun testFormatDuration() {
        assertEquals("", MediaStoreScanner.formatDuration(null))
        assertEquals("", MediaStoreScanner.formatDuration(0L))
        assertEquals("", MediaStoreScanner.formatDuration(-500L))

        // 45 seconds
        assertEquals("00:45", MediaStoreScanner.formatDuration(45_000L))
        // 2 minutes 5 seconds
        assertEquals("02:05", MediaStoreScanner.formatDuration(125_000L))
        // 1 hour, 1 minute, 5 seconds
        assertEquals("1:01:05", MediaStoreScanner.formatDuration(3_665_000L))
    }

    @Test
    fun testScannedMediaItem_MappingToPhoto() {
        val item = ScannedMediaItem(
            id = "101",
            contentUri = "content://media/external/video/media/101",
            mimeType = "video/mp4",
            photoType = "mp4",
            size = 15_420_000L,
            dateAdded = 1700000000L,
            dateModified = 1700000050L,
            dateTaken = 1700000000000L,
            mediaType = "VIDEO",
            width = 1920,
            height = 1080,
            duration = 45_000L,
            bucketId = 1234L,
            bucketName = "Camera",
            deviceId = "dev_abc"
        )

        assertTrue(item.isVideo)

        val photo = item.toPhoto()
        assertEquals("101", photo.localId)
        assertNull(photo.remoteId)
        assertEquals("mp4", photo.photoType)
        assertEquals("content://media/external/video/media/101", photo.pathUri)
        assertEquals("NONE", photo.uploadStatus)
        assertEquals("dev_abc", photo.deviceId)
        assertEquals("VIDEO", photo.mediaType)
        assertEquals(1920, photo.width)
        assertEquals(1080, photo.height)
        assertEquals(45_000L, photo.duration)

        val uiPhoto = item.toLocalUiPhoto()
        assertEquals("101", uiPhoto.localId)
        assertEquals("content://media/external/video/media/101", uiPhoto.pathUri)
        assertEquals(1700000000000L, uiPhoto.displayDateMillis)
        assertEquals("VIDEO", uiPhoto.mediaType)
        assertEquals(45_000L, uiPhoto.duration)
        assertTrue(uiPhoto.isVideo)
    }

    @Test
    fun testPhotoDao_FixLegacyVideoMediaTypes() = runBlocking {
        val photoDao = db.photoDao()

        // Insert legacy video with mediaType = 'IMAGE'
        val legacyVideo = Photo(
            localId = "legacy_1",
            remoteId = null,
            photoType = "mp4",
            pathUri = "content://media/external/video/media/legacy_1",
            mediaType = "IMAGE"
        )
        // Insert normal image with mediaType = 'IMAGE'
        val legacyImage = Photo(
            localId = "legacy_2",
            remoteId = null,
            photoType = "jpg",
            pathUri = "content://media/external/images/media/legacy_2",
            mediaType = "IMAGE"
        )

        photoDao.insertPhotos(legacyVideo, legacyImage)

        // Run repair
        val fixedCount = photoDao.fixLegacyVideoMediaTypes()
        assertEquals(1, fixedCount)

        val updatedVideo = photoDao.getPhotoByLocalId("legacy_1")
        assertNotNull(updatedVideo)
        assertEquals("VIDEO", updatedVideo!!.mediaType)

        val updatedImage = photoDao.getPhotoByLocalId("legacy_2")
        assertNotNull(updatedImage)
        assertEquals("IMAGE", updatedImage!!.mediaType)
    }

    @Test
    fun testPhotoDao_InsertIgnorePreservesExistingUploadedStatus() = runBlocking {
        val photoDao = db.photoDao()

        // Existing photo that was already backed up to cloud
        val existingPhoto = Photo(
            localId = "p_100",
            remoteId = "cloud_msg_999",
            photoType = "jpg",
            pathUri = "content://media/external/images/media/p_100",
            contentHash = "abcdef123456",
            uploadStatus = "UPLOADED",
            deviceId = "phone_1"
        )
        photoDao.insertPhotos(existingPhoto)

        // MediaStore scanner scans the item anew as an un-uploaded photo
        val rescannedPhoto = Photo(
            localId = "p_100",
            remoteId = null,
            photoType = "jpg",
            pathUri = "content://media/external/images/media/p_100",
            contentHash = null,
            uploadStatus = "NONE",
            deviceId = "phone_1"
        )
        photoDao.insertPhotos(rescannedPhoto)

        // Verify that existing record was NOT overwritten!
        val preserved = photoDao.getPhotoByLocalId("p_100")
        assertNotNull(preserved)
        assertEquals("cloud_msg_999", preserved!!.remoteId)
        assertEquals("UPLOADED", preserved.uploadStatus)
        assertEquals("abcdef123456", preserved.contentHash)
    }
}
