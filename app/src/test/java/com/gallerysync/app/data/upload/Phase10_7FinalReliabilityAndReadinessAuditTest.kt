package com.gallerysync.app.data.upload

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gallerysync.app.data.chunking.ChunkConfiguration
import com.gallerysync.app.data.chunking.ChunkDescriptor
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.mediastore.MediaStoreScanner
import com.gallerysync.app.data.restoration.ReconstructionResult
import com.gallerysync.app.data.restoration.VideoReconstructionEngine
import com.gallerysync.app.player.LocalMediaUriValidator
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
class Phase10_7FinalReliabilityAndReadinessAuditTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    private val testChannelId = -1003924279260L
    private val testBotToken = "9051118019:AAHTestTokenStringRedactedForSafety123456"

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
    // SECTION 1: LARGE-FILE CHUNKING & SCALING STRESS TESTING (Section 10)
    // =========================================================================

    @Test
    fun largeFileChunking_stressScaling_16mb_100mb_500mb_1gb_contiguousAndBounded() {
        val sizes = listOf(
            16_777_216L,           // 16 MiB (1 chunk)
            104_857_600L,          // 100 MiB (7 chunks: 6 x 16 MiB + 1 x 4 MiB)
            524_288_000L,          // 500 MiB (32 chunks: 31 x 16 MiB + 1 x 4 MiB)
            1_073_741_824L         // 1 GiB (64 chunks: 64 x 16 MiB)
        )

        val config = ChunkConfiguration.DEFAULT // 16 MiB chunks

        for (size in sizes) {
            val descriptors = ChunkDescriptor.calculateDescriptors(size, config)
            assertFalse("Descriptors must not be empty for size $size", descriptors.isEmpty())

            var expectedStart = 0L
            for (i in descriptors.indices) {
                val d = descriptors[i]
                assertEquals("Chunk index must match sequential position", i, d.chunkIndex)
                assertEquals("Chunk start offset must match expected offset", expectedStart, d.startOffset)
                assertTrue("Chunk size must be <= 16 MiB", d.chunkSize <= config.chunkSize)
                assertEquals("End offset must equal start + size", d.startOffset + d.chunkSize, d.endOffset)
                expectedStart = d.endOffset
            }

            assertEquals("Last chunk end offset must exactly equal total file size", size, expectedStart)
            val sumBytes = descriptors.sumOf { it.chunkSize }
            assertEquals("Sum of all chunk sizes must equal total file size without overflow", size, sumBytes)
        }
    }

    @Test
    fun largeFileChunking_10gb_zeroIntegerOverflow() {
        val tenGigabytes = 10L * 1024L * 1024L * 1024L // 10,737,418,240 bytes
        val descriptors = ChunkDescriptor.calculateDescriptors(tenGigabytes, ChunkConfiguration.DEFAULT)
        assertEquals("10 GiB requires 640 x 16 MiB chunks", 640, descriptors.size)

        val last = descriptors.last()
        assertEquals("Last chunk index must be 639", 639, last.chunkIndex)
        assertEquals("Last chunk end must be 10 GiB", tenGigabytes, last.endOffset)
        assertEquals("All chunks in exact 10 GiB file are 16 MiB", 16_777_216L, last.chunkSize)
    }

    // =========================================================================
    // SECTION 2: STREAMING MEMORY BOUNDEDNESS & TEMPORARY FILE CLEANUP (Section 10)
    // =========================================================================

    @Test
    fun reconstructionEngine_temporaryFileCleanup_onFailureAndCancellation() = runBlocking {
        val engine = VideoReconstructionEngine()
        val outputFile = File(tempFolder.root, "cleanup_test_output.mp4")
        if (outputFile.exists()) outputFile.delete()

        // Missing chunk test: chunk 0 is missing
        val descriptors = listOf(
            com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor(
                index = 0,
                startOffset = 0L,
                endOffsetExclusive = 1024L,
                sizeBytes = 1024L,
                sha256 = "dummy_sha",
                telegramFileId = "dummy_fid"
            )
        )

        val result = engine.reconstruct(
            chunkFiles = emptyMap(),
            chunkDescriptors = descriptors,
            expectedTotalSize = 1024L,
            expectedFullSha256 = "dummy_full_sha",
            outputFile = outputFile
        )

        assertTrue("Reconstruction must fail gracefully when chunk is missing", result is ReconstructionResult.ChunkMissing)
        assertFalse("Output file must not exist on failure", outputFile.exists())
        val partFile = File(outputFile.parentFile, "${outputFile.name}.part")
        assertFalse("Staging .part file must be cleaned up on failure", partFile.exists())
    }

    @Test
    fun reconstructionEngine_cancellation_abortsImmediatelyWithoutLeak() = runBlocking {
        val engine = VideoReconstructionEngine()
        val outputFile = File(tempFolder.root, "cancelled_output.mp4")
        if (outputFile.exists()) outputFile.delete()

        val chunkFile = tempFolder.newFile("chunk_0.bin")
        chunkFile.writeBytes(ByteArray(2048) { 0x55.toByte() })

        val descriptors = listOf(
            com.gallerysync.app.data.catalog.models.ManifestChunkDescriptor(
                index = 0,
                startOffset = 0L,
                endOffsetExclusive = 2048L,
                sizeBytes = 2048L,
                sha256 = "dummy_sha",
                telegramFileId = "fid_0"
            )
        )

        // Cancellation requested immediately
        val result = engine.reconstruct(
            chunkFiles = mapOf(0 to chunkFile),
            chunkDescriptors = descriptors,
            expectedTotalSize = 2048L,
            expectedFullSha256 = "dummy_full",
            outputFile = outputFile,
            isCancelled = { true }
        )

        assertTrue("Result must be Cancelled", result is ReconstructionResult.Cancelled)
        assertFalse("Output file must not be promoted on cancellation", outputFile.exists())
        val partFile = File(outputFile.parentFile, "${outputFile.name}.part")
        assertFalse("Temporary .part file must be deleted on cancellation", partFile.exists())
    }

    // =========================================================================
    // SECTION 3: SECURITY, CREDENTIALS & COMPONENT PROTECTION (Section 11)
    // =========================================================================

    @Test
    fun security_componentProtection_receiversRequireDumpPermission() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val manifestText = if (manifestFile.exists()) {
            manifestFile.readText()
        } else {
            File("app/src/main/AndroidManifest.xml").readText()
        }

        assertTrue(
            "LiveCatalogValidationReceiver must require android.permission.DUMP",
            manifestText.contains("LiveCatalogValidationReceiver") &&
            manifestText.contains("android:permission=\"android.permission.DUMP\"")
        )

        assertTrue(
            "LiveRestorationValidationReceiver must require android.permission.DUMP",
            manifestText.contains("LiveRestorationValidationReceiver") &&
            manifestText.contains("android:permission=\"android.permission.DUMP\"")
        )
    }

    @Test
    fun security_tokenRedaction_neverExposedInErrorClassification() {
        val rawSensitiveError = "Connection failed to https://api.telegram.org/bot$testBotToken/sendDocument: connection reset"
        val classified = TelegramUploadDispatcher.classifyError(Exception(rawSensitiveError))

        // Ensure user-facing error message or string representation does not expose the raw token
        assertFalse(
            "Classified error must not leak raw bot token",
            classified.errorMessage.contains(testBotToken)
        )
    }

    // =========================================================================
    // SECTION 4: QUEUE DEDUPLICATION, LEASE & RETRY SCHEDULING (Section 4)
    // =========================================================================

    @Test
    fun uploadQueue_transactionalDeduplication_avoidsDuplicateEntries() = runBlocking {
        val photoDao = database.photoDao()
        val queueDao = database.uploadQueueDao()

        val photo = Photo(
            localId = "dedup_photo_1",
            pathUri = "content://media/external/images/media/1001",
            photoType = "jpg",
            uploadStatus = Photo.STATUS_NONE,
            mediaType = "IMAGE"
        )
        photoDao.insertPhotos(photo)

        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), "periodic")
        assertEquals("Initial queue size must be 1", 1, queueDao.getPending().size)

        // Repeated enqueue of the exact same item must be ignored
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), "periodic")
        assertEquals("Queue size must remain 1 after repeated enqueue", 1, queueDao.getPending().size)
    }

    @Test
    fun uploadQueue_holdingStateProtection_neverEnqueuedForAutoBackup() = runBlocking {
        val photoDao = database.photoDao()
        val queueDao = database.uploadQueueDao()

        val holdingPhoto = Photo(
            localId = "holding_photo_9958",
            pathUri = "content://media/external/images/media/9958",
            photoType = "jpg",
            uploadStatus = "HOLDING",
            mediaType = "IMAGE"
        )
        photoDao.insertPhotos(holdingPhoto)

        val pending = photoDao.getAllPendingUpload()
        assertFalse("HOLDING photos must be excluded from getAllPendingUpload", pending.any { it.localId == "holding_photo_9958" })

        UploadQueueRepository.enqueueMediaForBackup(context, pending, "periodic")
        assertEquals("No items should be enqueued from HOLDING records", 0, queueDao.getPending().size)
    }

    @Test
    fun uploadQueue_staleLeaseRecovery_reclaimsDeadWorkerJobs() = runBlocking {
        val queueDao = database.uploadQueueDao()

        val staleJob = UploadQueue(
            id = 1,
            localId = "stale_local_1",
            pathUri = "content://media/external/images/media/101",
            status = "IN_PROGRESS",
            workerType = "periodic",
            workManagerId = "dead_worker_uuid",
            startedAt = System.currentTimeMillis() - (15 * 60 * 1000L), // 15 mins ago (lease expired > 10m)
            mediaType = "IMAGE"
        )
        queueDao.insert(staleJob)

        val recoveredCount = UploadQueueRepository.recoverStaleUploads()
        assertEquals("One stale job should be recovered", 1, recoveredCount)

        val refreshed = queueDao.getById(1)
        assertNotNull(refreshed)
        assertEquals("Status should revert to PENDING", "PENDING", refreshed?.status)
        assertEquals("Worker claim should be cleared", null, refreshed?.workManagerId)
    }

    @Test
    fun uploadQueue_rateLimitBackoff_respectsRetryAfterTimestamp() = runBlocking {
        val queueDao = database.uploadQueueDao()

        val job = UploadQueue(
            id = 2,
            localId = "rate_limit_local",
            pathUri = "content://media/external/images/media/102",
            status = "IN_PROGRESS",
            workerType = "periodic",
            workManagerId = "worker_1",
            startedAt = System.currentTimeMillis(),
            mediaType = "IMAGE"
        )
        queueDao.insert(job)

        val retryAfterSeconds = 45
        val error = TelegramUploadDispatcher.UploadError.RateLimited(retryAfterSeconds)
        UploadQueueRepository.recordFailure(job.id, job.localId, error)

        val updated = queueDao.getById(2)
        assertNotNull(updated)
        assertEquals("Status must be RETRY_WAIT", "RETRY_WAIT", updated?.status)
        assertTrue(
            "Scheduled retry timestamp must be set in the future (~45s)",
            (updated?.scheduledRetryAt ?: 0L) >= System.currentTimeMillis() + 40_000L
        )
    }

    // =========================================================================
    // SECTION 5: VIDEO PLAYER, LOCAL URI VALIDATOR & FORMATTING (Section 3 & 7)
    // =========================================================================

    @Test
    fun localMediaUriValidator_acceptsOnlyLocalSchemes() {
        // Valid local content and file URIs
        assertTrue(LocalMediaUriValidator.isLocalMediaUri("content://media/external/video/media/123"))
        assertTrue(LocalMediaUriValidator.isLocalMediaUri("file:///storage/emulated/0/Download/video.mp4"))
        assertTrue(LocalMediaUriValidator.isLocalMediaUri(Uri.parse("content://com.android.providers.media/documents/video%3A123")))

        // Strictly rejected remote URLs and Telegram IDs
        assertFalse(LocalMediaUriValidator.isLocalMediaUri("https://api.telegram.org/file/botToken/video.mp4"))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri("http://example.com/video.mp4"))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri("BAACAgUAAyEGAATp57_cAAIL3..."))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(""))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(null as Uri?))
    }

    @Test
    fun mediaStoreScanner_durationFormatting_handlesSecondsMinutesHours() {
        assertEquals("", MediaStoreScanner.formatDuration(null))
        assertEquals("", MediaStoreScanner.formatDuration(0L))
        assertEquals("00:05", MediaStoreScanner.formatDuration(5_000L))
        assertEquals("03:45", MediaStoreScanner.formatDuration(225_000L))
        assertEquals("1:15:30", MediaStoreScanner.formatDuration(4_530_000L))
    }

    // =========================================================================
    // SECTION 6: LEGACY DIRECT VIDEO DOWNLOAD CEILING COMPATIBILITY (Section 7)
    // =========================================================================

    @Test
    fun legacyVideo_hostedBotDownloadCeiling_rejectionBehavior() {
        val legacyDirectSize = 25_000_000L // 25 MB > 20,000,000 hosted ceiling
        val fitsHostedCeiling = legacyDirectSize <= com.gallerysync.app.data.restoration.RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES
        assertFalse("Legacy direct video of 25 MB exceeds Telegram hosted Bot API getFile limit", fitsHostedCeiling)

        // Ensure production direct upload threshold is aligned to 16 MiB
        assertEquals(
            "Production DIRECT_UPLOAD_MAX_BYTES must remain exactly 16 MiB",
            16_777_216L,
            ChunkConfiguration.DIRECT_UPLOAD_MAX_BYTES
        )
    }
}
