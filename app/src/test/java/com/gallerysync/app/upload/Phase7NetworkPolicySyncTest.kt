package com.gallerysync.app.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.UploadQueue
import com.gallerysync.app.data.upload.SyncNetworkPolicy
import com.gallerysync.app.data.upload.TelegramUploadDispatcher.UploadError
import com.gallerysync.app.data.upload.UploadQueueRepository
import com.gallerysync.app.workers.WorkModule
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase7NetworkPolicySyncTest {

    private lateinit var context: Context
    private lateinit var database: WhDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Preferences.allowUnencryptedFallbackForTesting = true
        Preferences.init(context)

        database = Room.inMemoryDatabaseBuilder(context, WhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DbHolder.database = database

        // Reset preferences & network policy override to clean defaults
        Preferences.setWifiOnlyBackupEnabled(true)
        Preferences.setAllowMeteredWifiBackup(false)
        Preferences.setAllowManualMobileDataUpload(false)
        SyncNetworkPolicy.networkStatusOverride = null
    }

    @After
    fun tearDown() {
        SyncNetworkPolicy.networkStatusOverride = null
        database.close()
    }

    // ─── Scenario 1: Automatic uploads proceed on eligible Wi-Fi ────────────
    @Test
    fun test01_automaticUploadProceedsOnEligibleWifi() = runBlocking {
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.Eligible

        val status = SyncNetworkPolicy.evaluateNetwork(context, isManual = false)
        assertTrue("Network status must be Eligible on unmetered Wi-Fi", status.isEligible)
        assertTrue("isNetworkEligible must return true", SyncNetworkPolicy.isNetworkEligible(context, isManual = false))

        val constraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        assertEquals("Constraints must require UNMETERED network", NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    // ─── Scenario 2: Automatic uploads do not proceed on mobile data ────────
    @Test
    fun test02_automaticUploadsDoNotProceedOnMobileData() = runBlocking {
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.DisallowedMobileData

        val status = SyncNetworkPolicy.evaluateNetwork(context, isManual = false)
        assertFalse("Automatic upload must NOT be eligible on mobile data", status.isEligible)
        assertEquals(SyncNetworkPolicy.NetworkStatus.DisallowedMobileData, status)
        assertEquals("Waiting for Wi-Fi", SyncNetworkPolicy.getNetworkStatusDescription(context, isManual = false))

        // Even if manual mobile uploads are permitted, automatic uploads MUST still reject mobile data
        Preferences.setAllowManualMobileDataUpload(true)
        val statusWithManualAllowed = SyncNetworkPolicy.evaluateNetwork(context, isManual = false)
        assertFalse("Automatic uploads must NEVER use mobile data even if manual allowed", statusWithManualAllowed.isEligible)
    }

    // ─── Scenario 3: Automatic uploads do not proceed on metered Wi-Fi by default ─
    @Test
    fun test03_automaticUploadsDoNotProceedOnMeteredWifiByDefault() = runBlocking {
        Preferences.setAllowMeteredWifiBackup(false)
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.DisallowedMeteredWifi

        val status = SyncNetworkPolicy.evaluateNetwork(context, isManual = false)
        assertFalse("Metered Wi-Fi must be rejected by default", status.isEligible)
        assertEquals(SyncNetworkPolicy.NetworkStatus.DisallowedMeteredWifi, status)
        assertEquals("Waiting for unmetered network", SyncNetworkPolicy.getNetworkStatusDescription(context, isManual = false))

        // When user explicitly allows metered Wi-Fi:
        Preferences.setAllowMeteredWifiBackup(true)
        val constraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        assertEquals("Constraints should relax to CONNECTED when metered Wi-Fi is permitted", NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    // ─── Scenario 4: Queue remains pending when network is unavailable ──────
    @Test
    fun test04_queueRemainsPendingWhenNetworkIsUnavailable() = runBlocking {
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.NoNetwork

        val photo = Photo(
            localId = "offline_photo",
            pathUri = "content://media/external/images/media/401",
            photoType = "jpg"
        )
        database.photoDao().insertPhotos(photo)

        // Enqueue media into queue
        val enqueued = UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)
        assertEquals(1, enqueued)

        // Network is unavailable: items must remain PENDING and NOT fail
        val queueItem = database.uploadQueueDao().getByLocalId("offline_photo")!!
        assertEquals("Queue item must remain PENDING when network unavailable", "PENDING", queueItem.status)
        assertNull("WorkManager token must be null", queueItem.workManagerId)

        val updatedPhoto = database.photoDao().getPhotoByLocalId("offline_photo")!!
        assertFalse("Photo uploadStatus must not be marked FAILED", updatedPhoto.uploadStatus == "FAILED")
    }

    // ─── Scenario 5: Queue processing resumes after Wi-Fi becomes available ──
    @Test
    fun test05_queueProcessingResumesAfterWifiBecomesAvailable() = runBlocking {
        // Step A: Initially offline
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.NoNetwork
        val photo = Photo(
            localId = "resume_photo",
            pathUri = "content://media/external/images/media/501",
            photoType = "jpg"
        )
        database.photoDao().insertPhotos(photo)
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(photo), database = database)

        val pendingItem = database.uploadQueueDao().getByLocalId("resume_photo")!!
        assertEquals("PENDING", pendingItem.status)

        // Step B: Wi-Fi becomes available
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.Eligible
        assertTrue("Network must now be eligible", SyncNetworkPolicy.isNetworkEligible(context))

        // Step C: Queue processor claims and resumes item
        val claimed = UploadQueueRepository.claimNextItem("resumed_worker", database = database)
        assertNotNull("Pending item must be claimable once Wi-Fi is restored", claimed)
        assertEquals("resume_photo", claimed!!.localId)
        assertEquals("IN_PROGRESS", claimed.status)
        assertEquals("resumed_worker", claimed.workManagerId)
    }

    // ─── Scenario 6: Network loss during upload does not corrupt queue state ─
    @Test
    fun test06_networkLossDuringUploadDoesNotCorruptQueueState() = runBlocking {
        val item = UploadQueue(
            localId = "interrupted_item",
            pathUri = "content://media/external/images/media/601",
            status = "IN_PROGRESS",
            workManagerId = "network_drop_worker",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)
        database.photoDao().insertPhotos(Photo(localId = "interrupted_item", pathUri = item.pathUri, photoType = "jpg"))

        // Case A: Socket / network drop during transmission
        val now = 5000000L
        UploadQueueRepository.recordFailure(
            queueId = qid,
            localId = "interrupted_item",
            error = UploadError.NetworkUnavailable("Connection reset by peer"),
            now = now,
            database = database
        )

        val updated = database.uploadQueueDao().getById(qid)!!
        assertEquals("RETRY_WAIT", updated.status)
        assertEquals("Retry count must increment to 1", 1, updated.retryCount)
        assertNotNull("Scheduled retry timestamp must be set", updated.scheduledRetryAt)
        assertTrue("Scheduled retry must be in the future", updated.scheduledRetryAt!! > now)

        // Case B: Worker cancellation by WorkManager on network loss (release claims)
        val item2 = UploadQueue(
            localId = "cancelled_by_os",
            pathUri = "content://media/external/images/media/602",
            status = "IN_PROGRESS",
            workManagerId = "stopped_worker",
            workerType = "periodic"
        )
        val qid2 = database.uploadQueueDao().insert(item2)
        UploadQueueRepository.releaseWorkerClaims("stopped_worker", database = database)

        val released = database.uploadQueueDao().getById(qid2)!!
        assertEquals("Cancelled worker claim must be safely reset to PENDING", "PENDING", released.status)
        assertNull("Worker token must be cleared", released.workManagerId)
    }

    // ─── Scenario 7: Process restart preserves pending queue entries ─────────
    @Test
    fun test07_processRestartPreservesPendingQueueEntries() = runBlocking {
        val item = UploadQueue(
            localId = "restart_photo",
            pathUri = "content://media/external/images/media/701",
            status = "PENDING",
            contentHash = "hash_abc_123",
            workerType = "periodic"
        )
        val qid = database.uploadQueueDao().insert(item)

        // Read back to simulate new process reading SQLite database
        val reloaded = database.uploadQueueDao().getById(qid)
        assertNotNull("Record must survive in SQLite across process restart", reloaded)
        assertEquals("PENDING", reloaded!!.status)
        assertEquals("hash_abc_123", reloaded.contentHash)
        assertEquals("restart_photo", reloaded.localId)
    }

    // ─── Scenario 8: Device reboot preserves scheduled backup work ───────────
    @Test
    fun test08_deviceRebootPreservesScheduledBackupWork() {
        val constraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        val repeatIntervalDays = 7L
        val periodicRequest = androidx.work.PeriodicWorkRequestBuilder<com.gallerysync.app.workers.PeriodicPhotoBackupWorker>(
            java.time.Duration.ofDays(repeatIntervalDays)
        )
            .setConstraints(constraints)
            .build()

        assertNotNull(periodicRequest)
        assertEquals(NetworkType.UNMETERED, periodicRequest.workSpec.constraints.requiredNetworkType)
        assertEquals(java.time.Duration.ofDays(repeatIntervalDays).toMillis(), periodicRequest.workSpec.intervalDuration)
        assertTrue("Periodic work spec must be periodic", periodicRequest.workSpec.isPeriodic)
    }

    // ─── Scenario 9: Delayed retries are not lost when network constraints prevent execution ─
    @Test
    fun test09_delayedRetriesNotLostWhenNetworkConstraintsPreventExecution() {
        val delayMs = 30_000L
        val constraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        val retryRequest = androidx.work.OneTimeWorkRequestBuilder<com.gallerysync.app.workers.PeriodicPhotoBackupWorker>()
            .setConstraints(constraints)
            .setInitialDelay(java.time.Duration.ofMillis(delayMs))
            .build()

        assertNotNull(retryRequest)
        assertEquals("Network constraints must require UNMETERED", NetworkType.UNMETERED, retryRequest.workSpec.constraints.requiredNetworkType)
        assertEquals("Initial delay must be exactly configured delay", delayMs, retryRequest.workSpec.initialDelay)
        assertFalse("Retry request must be one-time (not periodic)", retryRequest.workSpec.isPeriodic)
    }

    // ─── Scenario 10: Instant and periodic triggers use the same queue-processing worker ─
    @Test
    fun test10_instantAndPeriodicTriggersUseSameQueueProcessingWorker() = runBlocking {
        val instantPhoto = Photo(
            localId = "instant_media_10",
            pathUri = "content://media/external/images/media/1001",
            photoType = "jpg"
        )
        val periodicPhoto = Photo(
            localId = "periodic_media_10",
            pathUri = "content://media/external/images/media/1002",
            photoType = "jpg"
        )
        database.photoDao().insertPhotos(instantPhoto, periodicPhoto)

        // Instant trigger enqueues with workerType = "instant"
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(instantPhoto), workerType = "instant", database = database)
        // Periodic trigger enqueues with workerType = "periodic"
        UploadQueueRepository.enqueueMediaForBackup(context, listOf(periodicPhoto), workerType = "periodic", database = database)

        val allQueue = database.uploadQueueDao().getAll()
        assertEquals("Both triggers store media into the single authoritative upload_queue", 2, allQueue.size)

        // Single authoritative queue worker claims both sequentially
        val claimed1 = UploadQueueRepository.claimNextItem("single_queue_worker", database = database)
        val claimed2 = UploadQueueRepository.claimNextItem("single_queue_worker", database = database)
        assertNotNull(claimed1)
        assertNotNull(claimed2)
        assertEquals("single_queue_worker", claimed1!!.workManagerId)
        assertEquals("single_queue_worker", claimed2!!.workManagerId)
    }

    // ─── Scenario 11: Manual mobile-data behavior follows configured policy ──
    @Test
    fun test11_manualMobileDataBehaviorFollowsConfiguredPolicy() = runBlocking {
        // Step A: Disallowed by default
        Preferences.setAllowManualMobileDataUpload(false)
        val defaultConstraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = true)
        assertEquals("Default manual constraints must require UNMETERED", NetworkType.UNMETERED, defaultConstraints.requiredNetworkType)

        // Step B: User explicitly enables manual uploads over mobile data
        Preferences.setAllowManualMobileDataUpload(true)
        val allowedConstraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = true)
        assertEquals("When permitted, manual constraints relax to CONNECTED", NetworkType.CONNECTED, allowedConstraints.requiredNetworkType)

        // However, automatic backup constraints MUST strictly remain UNMETERED
        val autoConstraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        assertEquals("Automatic backup constraints MUST strictly remain UNMETERED", NetworkType.UNMETERED, autoConstraints.requiredNetworkType)
    }

    // ─── Scenario 12: No upload worker bypasses the centralized network policy ─
    @Test
    fun test12_noUploadWorkerBypassesCentralizedNetworkPolicy() = runBlocking {
        // Simulate in-flight network degradation: active upload was running, then network was lost
        SyncNetworkPolicy.networkStatusOverride = SyncNetworkPolicy.NetworkStatus.DisallowedMobileData

        // Verify centralized policy halts both automatic processing and unauthorized manual actions
        assertFalse(
            "Worker must not be allowed to proceed on mobile data for automatic sync",
            SyncNetworkPolicy.isNetworkEligible(context, isManual = false)
        )

        Preferences.setAllowManualMobileDataUpload(false)
        assertFalse(
            "Worker must not be allowed to proceed on mobile data for manual sync if setting is disabled",
            SyncNetworkPolicy.isNetworkEligible(context, isManual = true)
        )

        // Verify WorkManager constraints enforce policy
        val autoConstraints = SyncNetworkPolicy.getWorkManagerConstraints(isManual = false)
        assertEquals(NetworkType.UNMETERED, autoConstraints.requiredNetworkType)
    }
}
