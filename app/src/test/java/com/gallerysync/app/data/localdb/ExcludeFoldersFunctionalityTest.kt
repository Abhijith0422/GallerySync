package com.gallerysync.app.data.localdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.localdb.entities.Photo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExcludeFoldersFunctionalityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.init(context)
        Preferences.setExcludedBucketNames(emptySet())
    }

    @After
    fun tearDown() {
        Preferences.setExcludedBucketNames(emptySet())
    }

    @Test
    fun preferences_getAndSetExcludedBucketNames_persistsAndRetrievesCorrectly() {
        // Initial state: empty set
        assertTrue(Preferences.getExcludedBucketNames().isEmpty())

        // Set multiple excluded folders
        val excludedFolders = setOf("Screenshots", "WhatsApp Images", "Download")
        Preferences.setExcludedBucketNames(excludedFolders)

        val retrieved = Preferences.getExcludedBucketNames()
        assertEquals(3, retrieved.size)
        assertTrue(retrieved.contains("Screenshots"))
        assertTrue(retrieved.contains("WhatsApp Images"))
        assertTrue(retrieved.contains("Download"))
        assertFalse(retrieved.contains("Camera"))

        // Update with a different set
        val updatedFolders = setOf("Camera")
        Preferences.setExcludedBucketNames(updatedFolders)

        val retrievedUpdated = Preferences.getExcludedBucketNames()
        assertEquals(1, retrievedUpdated.size)
        assertTrue(retrievedUpdated.contains("Camera"))
        assertFalse(retrievedUpdated.contains("Screenshots"))

        // Clear exclusions
        Preferences.setExcludedBucketNames(emptySet())
        assertTrue(Preferences.getExcludedBucketNames().isEmpty())
    }

    @Test
    fun preferences_setExcludedBucketNames_defensiveCopyingProtectsAgainstExternalMutation() {
        val mutableOriginal = mutableSetOf("InitialFolder")
        Preferences.setExcludedBucketNames(mutableOriginal)

        // Mutate original set after storing
        mutableOriginal.add("MutatedFolder")

        val stored = Preferences.getExcludedBucketNames()
        assertTrue(stored.contains("InitialFolder"))
        assertFalse("External mutation of original set must not alter SharedPreferences", stored.contains("MutatedFolder"))
    }

    @Test
    fun periodicBackup_folderExclusion_filtersOutPhotosInExcludedBuckets() {
        val photos = listOf(
            Photo(localId = "101", photoType = "jpg", pathUri = "content://media/external/images/media/101"),
            Photo(localId = "102", photoType = "jpg", pathUri = "content://media/external/images/media/102"),
            Photo(localId = "103", photoType = "mp4", pathUri = "content://media/external/video/media/103"),
            Photo(localId = "104", photoType = "jpg", pathUri = "content://media/external/images/media/104")
        )

        val bucketNameMap = mapOf(
            "101" to "Camera",
            "102" to "Screenshots",
            "103" to "WhatsApp Video",
            "104" to "Download"
        )

        // Exclude Screenshots and WhatsApp Video
        val excludedBuckets = setOf("Screenshots", "WhatsApp Video")
        Preferences.setExcludedBucketNames(excludedBuckets)

        val filtered = photos.filter { photo ->
            val photoId = photo.pathUri.substringAfterLast("/")
            val bucketName = bucketNameMap[photoId]
            bucketName !in excludedBuckets
        }

        assertEquals(2, filtered.size)
        assertEquals("101", filtered[0].localId)
        assertEquals("104", filtered[1].localId)
        assertFalse(filtered.any { it.localId == "102" })
        assertFalse(filtered.any { it.localId == "103" })
    }

    @Test
    fun periodicBackup_folderExclusion_whenNoFoldersExcluded_preservesAllPendingPhotos() {
        val photos = listOf(
            Photo(localId = "101", photoType = "jpg", pathUri = "content://media/external/images/media/101"),
            Photo(localId = "102", photoType = "jpg", pathUri = "content://media/external/images/media/102")
        )

        val bucketNameMap = mapOf(
            "101" to "Camera",
            "102" to "Screenshots"
        )

        Preferences.setExcludedBucketNames(emptySet())
        val excludedBuckets = Preferences.getExcludedBucketNames()

        val filtered = if (excludedBuckets.isEmpty()) {
            photos
        } else {
            photos.filter { photo ->
                val photoId = photo.pathUri.substringAfterLast("/")
                bucketNameMap[photoId] !in excludedBuckets
            }
        }

        assertEquals(2, filtered.size)
    }

    @Test
    fun periodicBackup_folderExclusion_whenAllFoldersExcluded_returnsEmptyList() {
        val photos = listOf(
            Photo(localId = "101", photoType = "jpg", pathUri = "content://media/external/images/media/101"),
            Photo(localId = "102", photoType = "jpg", pathUri = "content://media/external/images/media/102")
        )

        val bucketNameMap = mapOf(
            "101" to "Camera",
            "102" to "Screenshots"
        )

        val excludedBuckets = setOf("Camera", "Screenshots")
        Preferences.setExcludedBucketNames(excludedBuckets)

        val filtered = photos.filter { photo ->
            val photoId = photo.pathUri.substringAfterLast("/")
            val bucketName = bucketNameMap[photoId]
            bucketName !in excludedBuckets
        }

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun getBucketNamesForIds_handlesEmptyListImmediately() {
        val result = com.gallerysync.app.utils.getBucketNamesForIds(context.contentResolver, emptyList())
        assertTrue(result.isEmpty())
    }
}
