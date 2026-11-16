package com.gallerysync.app.ui.main.screens.uploads

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.Preferences
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.VideoRestorationJob
import com.gallerysync.app.data.restoration.RestorationStatus
import com.gallerysync.app.data.restoration.VideoRestorationManager
import com.gallerysync.app.utils.getBucketNamesForIds
import com.gallerysync.app.utils.getDateAddedForIds
import com.gallerysync.app.workers.InstantPhotoUploadWorker
import com.gallerysync.app.workers.PeriodicPhotoBackupWorker
import com.gallerysync.app.workers.WorkModule
import com.gallerysync.app.data.upload.UploadQueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

/**
 * Reusable duration formatter for media items.
 * MediaStore and Room database duration is stored in milliseconds.
 */
object DurationFormatter {
    /**
     * Formats duration in milliseconds (as stored in MediaStore and Room) to "mm:ss" or "h:mm:ss".
     * Expected examples:
     * - 0 ms (0 sec) -> "00:00"
     * - 74_000 ms (74 sec) -> "01:14"
     * - 125_000 ms (125 sec) -> "02:05"
     * - 3_661_000 ms (3661 sec) -> "1:01:01"
     * Null, zero, and negative values return "00:00".
     */
    fun formatDurationMillis(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "00:00"
        val totalSeconds = (durationMs + 500L) / 1000L
        return formatDurationSeconds(totalSeconds)
    }

    /**
     * Formats duration in seconds to "mm:ss" or "h:mm:ss".
     * Expected examples:
     * - 0 seconds -> "00:00"
     * - 74 seconds -> "01:14"
     * - 125 seconds -> "02:05"
     * - 3661 seconds -> "1:01:01"
     * Null, zero, and negative values return "00:00".
     */
    fun formatDurationSeconds(seconds: Long?): String {
        if (seconds == null || seconds <= 0L) return "00:00"
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remainingSeconds = seconds % 60L
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
        }
    }

    /**
     * Formats video duration. Defaults to project database unit (milliseconds).
     */
    fun formatDuration(durationMs: Long?): String = formatDurationMillis(durationMs)
}

/**
 * Reusable top-level duration formatter helper.
 */
fun formatDuration(durationMs: Long?): String = DurationFormatter.formatDuration(durationMs)

data class UploadUiItem(
    val id: String,
    val type: UploadType,
    val status: UploadStatus,
    val progress: Float = 0f,
    val progressText: String = "",
    val thumbnailUri: Any? = null,
    val fileName: String? = null,
    val totalItems: Int = 1,
    val isCancellable: Boolean = true,
    val localPhotoId: String? = null,
    val localId: String? = null,
    val speed: String? = null,
    val eta: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isVideo: Boolean = false,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    companion object {
        fun formatDuration(durationMs: Long?): String = DurationFormatter.formatDuration(durationMs)
        fun formatDurationMillis(durationMs: Long?): String = DurationFormatter.formatDurationMillis(durationMs)
        fun formatDurationSeconds(seconds: Long?): String = DurationFormatter.formatDurationSeconds(seconds)
    }
}

enum class UploadType {
    Selective,   // User manually chose these
    Background,  // Auto backup running in background
    Instant      // Expedited single photo sync
}

enum class UploadStatus {
    Queued,
    InProgress,
    Completed,
    Failed,
    Cancelled
}

class ManageUploadsViewModel @JvmOverloads constructor(
    application: Application,
    coroutineScope: kotlinx.coroutines.CoroutineScope? = null
) : AndroidViewModel(application) {

    private val scope: kotlinx.coroutines.CoroutineScope = coroutineScope ?: viewModelScope

    // UI-dismissed synced item IDs — persisted in SharedPreferences across app relaunches
    private val _dismissedIds = MutableStateFlow<Set<String>>(
        Preferences.getStringSet("dismissedSyncedIds", emptySet())
    )

    // Dismissed work IDs (failed/cancelled items dismissed by user) — runtime only
    private val _dismissedWorkIds = MutableStateFlow<Set<String>>(emptySet())

    // Retrying work IDs — items currently being retried (shown as Queued instantly)
    private val _retryingIds = MutableStateFlow<Set<String>>(emptySet())

    private val workManager: WorkManager? = try {
        WorkManager.getInstance(application)
    } catch (e: IllegalStateException) {
        Log.w("ManageUploadsVM", "WorkManager not initialized in environment: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w("ManageUploadsVM", "WorkManager unavailable: ${e.message}")
        null
    }

    // Observe manual backups (tagged "manual_backup")
    private val manualBackupFlow = (workManager?.getWorkInfosByTagFlow("manual_backup") ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .onStart { emit(emptyList()) }

    // Observe instant uploads (tagged "instant_upload")
    private val instantUploadFlow = (workManager?.getWorkInfosByTagFlow("instant_upload") ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .onStart { emit(emptyList()) }

    // Observe periodic/auto backups (by unique work name)
    private val periodicBackupFlow = (workManager?.getWorkInfosForUniqueWorkFlow(WorkModule.PERIODIC_PHOTO_BACKUP_WORK) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .onStart { emit(emptyList()) }

    // All uploads from DB (RemotePhoto table)
    private val remotePhotosFlow = DbHolder.database.remotePhotoDao().getAllFlow()
        .onStart { emit(emptyList()) }
 
    // Track start times for running workers to calculate speed/ETA
    private val workStartTimes = mutableMapOf<UUID, Long>()

    // Selection state for multi-select
    private val _selectedIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun toggleSelection(id: String) {
        _selectedIds.value = if (_selectedIds.value.contains(id)) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    // Inside ManageUploadsViewModel.kt
    fun refresh() {
        scope.launch {
            _isRefreshing.value = true
            // Call your data loading logic here
            // loadUploads()
            _isRefreshing.value = false
        }
    }
    fun batchCancel() {
        _selectedIds.value.forEach { cancelUpload(it) }
        clearSelection()
    }

    fun batchRetry() {
        // We'd need to re-enqueue specifically these, but retryAllFailed is more general.
        // For now, let's just trigger the general retry if any failed items are selected.
        retryAllFailed()
        clearSelection()
    }

    fun batchClearHistory() {
        val idsToClear = _selectedIds.value.toList()
        scope.launch {
            DbHolder.database.remotePhotoDao().deleteByRemoteIds(idsToClear)
        }
        // Also try to cancel/prune any work items selected
        _selectedIds.value.forEach { id ->
            val uuid = try { UUID.fromString(id) } catch (e: Exception) { null }
            if (uuid != null) {
                workManager?.cancelWorkById(uuid)
            }
        }
        clearSelection()
    }

    // Refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Undo state
    private var recentlyDeletedItem: com.gallerysync.app.data.localdb.entities.RemotePhoto? = null
    private var undoJob: Job? = null

    data class LocalPhotoLookups(
        val pathMap: Map<String, String> = emptyMap(),
        val idMap: Map<String, String> = emptyMap(),
        val photosByLocalId: Map<String, Photo> = emptyMap()
    )

    private val localPhotoLookupsFlow: Flow<LocalPhotoLookups> = DbHolder.database.photoDao().getAllFlow()
        .onStart { emit(emptyList()) }
        .map { photos -> 
            val pathMap = photos.filter { it.remoteId != null }.associate { it.remoteId!! to it.pathUri }
            val idMap = photos.filter { it.remoteId != null }.associate { it.remoteId!! to it.localId }
            val photosByLocalId = photos.associateBy { it.localId }
            LocalPhotoLookups(pathMap, idMap, photosByLocalId)
        }

    private val remoteToLocalPathMapFlow: Flow<Map<String, String>> = localPhotoLookupsFlow
        .map { it.pathMap }

    private val remoteToLocalIdMapFlow: Flow<Map<String, String>> = localPhotoLookupsFlow
        .map { it.idMap }

    // Queued Photos (from DB — genuinely pending upload, excluding excluded folders and holding items)
    val queuedPhotos: StateFlow<List<Photo>> = DbHolder.database.photoDao().getPendingUploadFlow()
        .onStart { emit(emptyList()) }
        .map { photos -> filterExcludedBucketPhotos(photos) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Holding Photos (unverified media from initial baseline / reinstall, safely held from auto upload)
    val holdingPhotos: StateFlow<List<Photo>> = DbHolder.database.photoDao().getAllHoldingFlow()
        .onStart { emit(emptyList()) }
        .map { photos -> filterExcludedBucketPhotos(photos) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val holdingCount: StateFlow<Int> = DbHolder.database.photoDao().getHoldingCountFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)

    fun uploadAllHolding() {
        scope.launch(Dispatchers.IO) {
            val count = DbHolder.database.photoDao().releaseAllHoldingToPending()
            Log.i("ManageUploadsVM", "Explicitly released $count holding photos to pending for upload")
            WorkModule.PeriodicBackup.enqueue(type = "manual_backup")
        }
    }

    // Video restoration jobs (Phase 10.5)
    val restorationJobs: StateFlow<List<VideoRestorationJob>> = DbHolder.database.videoRestorationDao()
        .getAllFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retryRestoration(restorationId: String) {
        scope.launch(Dispatchers.IO) {
            DbHolder.database.videoRestorationDao().updateStatus(restorationId, RestorationStatus.PENDING)
            WorkModule.VideoRestoration(restorationId).enqueue()
        }
    }

    fun cancelRestoration(restorationId: String) {
        scope.launch(Dispatchers.IO) {
            WorkModule.VideoRestoration(restorationId).cancel()
            DbHolder.database.videoRestorationDao().updateStatus(restorationId, RestorationStatus.CANCELLED)
        }
    }

    fun deleteRestoration(restorationId: String) {
        scope.launch(Dispatchers.IO) {
            WorkModule.VideoRestoration(restorationId).cancel()
            DbHolder.database.videoRestorationDao().deleteById(restorationId)
            VideoRestorationManager.defaultInstance.cleanupAllStaging(getApplication(), restorationId)
        }
    }

    private suspend fun filterExcludedBucketPhotos(photos: List<Photo>): List<Photo> {
        if (photos.isEmpty()) return photos
        val excludedNames = Preferences.getExcludedBucketNames()
        val syncMode = Preferences.getSyncMode()
        val newOnlyTimestamp = Preferences.getSyncNewOnlyTimestamp()
        if (excludedNames.isEmpty() && syncMode != Preferences.SYNC_MODE_NEW_ONLY) return photos
        val contentResolver = getApplication<Application>().contentResolver
        val ids = photos.mapNotNull { it.pathUri.substringAfterLast("/").toLongOrNull() }
        var filtered = photos

        if (excludedNames.isNotEmpty()) {
            val bucketNameMap = getBucketNamesForIds(contentResolver, ids)
            filtered = filtered.filter { photo ->
                val photoId = photo.pathUri.substringAfterLast("/")
                val bucketName = bucketNameMap[photoId]
                bucketName !in excludedNames
            }
        }

        if (syncMode == Preferences.SYNC_MODE_NEW_ONLY && newOnlyTimestamp > 0L) {
            val dateAddedMap = getDateAddedForIds(contentResolver, ids)
            filtered = filtered.filter { photo ->
                val photoId = photo.pathUri.substringAfterLast("/")
                val dateAdded = dateAddedMap[photoId] ?: 0L
                dateAdded >= newOnlyTimestamp
            }
        }

        return filtered
    }

    // Raw active work (before thumbnail enrichment)
    private val rawWorkFlow = combine(
        manualBackupFlow,
        instantUploadFlow,
        periodicBackupFlow,
        localPhotoLookupsFlow,
        _dismissedWorkIds
    ) { manual, instant, periodic, lookups, dismissedIds ->
        val workMap = mutableMapOf<UUID, Pair<WorkInfo, UploadType>>()
        manual.forEach { workMap[it.id] = it to UploadType.Selective }
        instant.forEach { if (!workMap.containsKey(it.id)) workMap[it.id] = it to UploadType.Instant }
        periodic.forEach { if (!workMap.containsKey(it.id)) workMap[it.id] = it to UploadType.Background }
        workMap.values.mapNotNull { (info, type) -> 
            val item = mapWorkInfoToUiItem(info, type, lookups.pathMap)
            if (item != null && item.id !in dismissedIds) item else null
        }
    }

    // Direct observation of persistent Room upload_queue
    private val queueItemsFlow: Flow<List<UploadUiItem>> = DbHolder.database.uploadQueueDao().getAllFlow()
        .onStart { emit(emptyList()) }
        .map { queueList ->
        val isAutoBackupEnabled = Preferences.getBoolean(Preferences.isAutoBackupEnabledKey, true)
        queueList.map { queue ->
            val status = when (queue.status) {
                "PENDING" -> UploadStatus.Queued
                "IN_PROGRESS" -> UploadStatus.InProgress
                "DONE", "COMPLETED" -> UploadStatus.Completed
                "FAILED", "EXCEEDS_LIMIT", "SOURCE_MISSING" -> UploadStatus.Failed
                "RETRY_WAIT" -> UploadStatus.Queued
                "CANCELLED" -> UploadStatus.Cancelled
                else -> UploadStatus.Queued
            }

            val isManual = queue.workerType == "manual"
            val networkStatus = com.gallerysync.app.data.upload.SyncNetworkPolicy.evaluateNetwork(application, isManual)

            val progressText = when (queue.status) {
                "PENDING" -> when {
                    !isAutoBackupEnabled && !isManual -> "Backup paused"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.NoNetwork -> "No network"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.DisallowedMobileData -> "Waiting for Wi-Fi"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.DisallowedMeteredWifi -> "Waiting for unmetered network"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.IneligibleOther -> "Waiting for Wi-Fi"
                    else -> "Queued for backup..."
                }
                "IN_PROGRESS" -> "Uploading to Telegram..."
                "DONE", "COMPLETED" -> "Synced to Cloud"
                "RETRY_WAIT" -> when {
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.NoNetwork -> "No network"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.DisallowedMobileData -> "Waiting for Wi-Fi"
                    networkStatus is com.gallerysync.app.data.upload.SyncNetworkPolicy.NetworkStatus.DisallowedMeteredWifi -> "Waiting for unmetered network"
                    else -> "Retry scheduled"
                }
                "EXCEEDS_LIMIT" -> "Exceeds 50 MB limit"
                "SOURCE_MISSING" -> "Source media missing"
                "FAILED" -> queue.errorMessage?.take(40) ?: "Upload failed"
                "CANCELLED" -> "Cancelled"
                else -> queue.status
            }
            val fileName = queue.pathUri.substringAfterLast("/")
            val isVideo = queue.mediaType == "VIDEO"
            UploadUiItem(
                id = queue.id.toString(),
                type = when (queue.workerType) {
                    "instant" -> UploadType.Instant
                    "manual" -> UploadType.Selective
                    else -> UploadType.Background
                },
                status = status,
                progress = if (status == UploadStatus.Completed) 1f else 0f,
                progressText = progressText,
                thumbnailUri = queue.pathUri,
                fileName = fileName,
                localPhotoId = queue.pathUri,
                localId = queue.localId,
                isCancellable = status == UploadStatus.InProgress || status == UploadStatus.Queued,
                timestamp = queue.createdAt,
                isVideo = isVideo,
                duration = queue.duration,
                width = queue.width,
                height = queue.height
            )
        }
    }

    // Combined active work with local thumbnails resolved
    private val allWorkFlow: Flow<List<UploadUiItem>> = combine(rawWorkFlow, queuedPhotos, _retryingIds, queueItemsFlow) { workItems, nonUploadedPhotos, retryingIds, queueItems ->
        val resolvedWork = workItems.map { item ->
            if (item.id in retryingIds) item.copy(status = UploadStatus.Queued, progressText = "Retrying...") else item
        }.map { item ->
            val matchedPhoto = nonUploadedPhotos.firstOrNull { p ->
                (item.fileName != null && p.pathUri.contains(item.fileName, ignoreCase = true)) ||
                (item.localId != null && p.localId == item.localId)
            }
            var updated = item
            if (updated.thumbnailUri == null && updated.localPhotoId == null && matchedPhoto != null) {
                updated = updated.copy(thumbnailUri = matchedPhoto.pathUri, localPhotoId = matchedPhoto.pathUri)
            }
            if (!updated.isVideo && matchedPhoto != null && matchedPhoto.mediaType == "VIDEO") {
                updated = updated.copy(
                    isVideo = true,
                    duration = updated.duration ?: matchedPhoto.duration,
                    width = updated.width ?: matchedPhoto.width,
                    height = updated.height ?: matchedPhoto.height
                )
            } else if (matchedPhoto != null && (updated.duration == null || updated.width == null)) {
                updated = updated.copy(
                    duration = updated.duration ?: matchedPhoto.duration,
                    width = updated.width ?: matchedPhoto.width,
                    height = updated.height ?: matchedPhoto.height
                )
            }
            updated
        }
        val queueLocalIds = queueItems.mapNotNull { it.localId }.toSet()
        queueItems + resolvedWork.filter { it.localId !in queueLocalIds }
    }

    // All uploads sorted by status and merging DB history
    val allUploads: StateFlow<List<UploadUiItem>> = combine(
        allWorkFlow, 
        remotePhotosFlow,
        localPhotoLookupsFlow,
        _dismissedIds
    ) { workItems, dbItems, lookups, dismissed ->
        val dbUploads = dbItems
            .filter { it.remoteId !in dismissed }
            .map { mapRemotePhotoToUiItem(it, lookups.pathMap, lookups.idMap, lookups.photosByLocalId) }
        val dbFileNames = dbItems.mapNotNull { it.fileName }.toSet()

        // Deduplicate: Keep work items only if they are not yet in the DB
        // or if they are still running/queued.
        val filteredWork = workItems.filter { item ->
            if (item.status == UploadStatus.Completed) {
                // If recently completed, only keep if it hasn't reached the DB yet
                return@filter item.fileName !in dbFileNames
            }
            // Keep all active/failed/queued items
            true
        }
        
        (filteredWork + dbUploads).sortedWith(
            compareByDescending<UploadUiItem> { it.status == UploadStatus.InProgress }
                .thenByDescending { it.status == UploadStatus.Queued }
                .thenByDescending { it.status == UploadStatus.Failed }
                .thenByDescending { it.timestamp }
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
 
    // Manual uploads only (manual backup + instant uploads + DB history of these types)
    val manualUploads: StateFlow<List<UploadUiItem>> = combine(
        allWorkFlow, 
        remotePhotosFlow,
        localPhotoLookupsFlow,
        _dismissedIds
    ) { workItems, dbItems, lookups, dismissed ->
        val manualWork = workItems.filter { it.type == UploadType.Selective || it.type == UploadType.Instant }
        val manualDb = dbItems
            .filter { it.uploadType == "manual_backup" || it.uploadType == "instant" }
            .filter { it.remoteId !in dismissed }
            .map { mapRemotePhotoToUiItem(it, lookups.pathMap, lookups.idMap, lookups.photosByLocalId) }
        
        val dbFileNames = manualDb.mapNotNull { it.fileName }.toSet()
        val filteredWork = manualWork.filter { 
            if (it.status == UploadStatus.Completed) it.fileName !in dbFileNames else true
        }
        
        val result: List<UploadUiItem> = (filteredWork + manualDb).sortedWith(
            compareByDescending<UploadUiItem> { it.status == UploadStatus.InProgress }
                .thenByDescending { it.timestamp }
        )
        result
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
 
    // Auto backup uploads only (Active work)
    val autoBackupUploads: StateFlow<List<UploadUiItem>> = allWorkFlow.map { items ->
        items.filter { it.type == UploadType.Background && it.status != UploadStatus.Completed }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
 
    // Failed uploads only
    val failedUploads: StateFlow<List<UploadUiItem>> = allWorkFlow.map { items ->
        items.filter { it.status == UploadStatus.Failed }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
 
    // Synced/Completed uploads history (All DB records + recently synced)
    val syncedUploads: StateFlow<List<UploadUiItem>> = combine(
        allWorkFlow,
        remotePhotosFlow,
        localPhotoLookupsFlow,
        _dismissedIds
    ) { workItems, dbItems, lookups, dismissed ->
        val dbUploads = dbItems
            .filter { it.remoteId !in dismissed }
            .map { mapRemotePhotoToUiItem(it, lookups.pathMap, lookups.idMap, lookups.photosByLocalId) }
        val dbFileNames = dbItems.mapNotNull { it.fileName }.toSet()
        
        // Include work items that are completed but not yet in DB
        val recentlyCompletedWork = workItems.filter { 
            it.status == UploadStatus.Completed && it.fileName !in dbFileNames && it.id !in dismissed
        }
        
        (recentlyCompletedWork + dbUploads).sortedByDescending { it.timestamp }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
 
    fun mapRemotePhotoToUiItem(
        remote: com.gallerysync.app.data.localdb.entities.RemotePhoto,
        localPathMap: Map<String, String>,
        localIdMap: Map<String, String> = emptyMap(),
        localPhotoMap: Map<String, Photo> = emptyMap()
    ): UploadUiItem {
        val localPath = localPathMap[remote.remoteId]
        val matchedLocalId = localIdMap[remote.remoteId] ?: remote.localId
        val matchedPhoto = (if (matchedLocalId != null) localPhotoMap[matchedLocalId] else null)
            ?: (if (remote.remoteId.isNotEmpty()) localPhotoMap[remote.remoteId] else null)

        val isVideo = remote.mediaType == "VIDEO" || matchedPhoto?.mediaType == "VIDEO"
        val duration = remote.duration ?: matchedPhoto?.duration
        val width = remote.width ?: matchedPhoto?.width
        val height = remote.height ?: matchedPhoto?.height

        val remoteThumb = if (localPath == null) {
            if (isVideo) {
                // Videos must only use previewRemoteId; never fall back to raw video fileId
                remote.previewRemoteId?.let { com.gallerysync.app.utils.coil.FileIdData(it) }
            } else {
                com.gallerysync.app.utils.coil.FileIdData(remote.previewRemoteId ?: remote.remoteId)
            }
        } else null
        return UploadUiItem(
            id = remote.remoteId,
            type = when (remote.uploadType) {
                "manual_backup" -> UploadType.Selective
                "instant" -> UploadType.Instant
                else -> UploadType.Background
            },
            status = UploadStatus.Completed,
            progress = 1f,
            progressText = "Synced to Cloud",
            thumbnailUri = localPath ?: remoteThumb,
            totalItems = 1,
            fileName = remote.fileName ?: if (isVideo) "Uploaded Video" else "Uploaded Photo",
            isCancellable = false,
            localPhotoId = localPath,
            localId = matchedLocalId,
            timestamp = remote.uploadedAt,
            isVideo = isVideo,
            duration = duration,
            width = width,
            height = height
        )
    }

    private fun mapWorkInfoToUiItem(
        workInfo: WorkInfo, 
        type: UploadType,
        localMap: Map<String, String> = emptyMap()
    ): UploadUiItem? {
        val status = when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> UploadStatus.Queued
            WorkInfo.State.RUNNING -> UploadStatus.InProgress
            WorkInfo.State.SUCCEEDED -> UploadStatus.Completed
            WorkInfo.State.FAILED -> UploadStatus.Failed
            WorkInfo.State.CANCELLED -> UploadStatus.Cancelled
            WorkInfo.State.BLOCKED -> UploadStatus.Queued
        }

        val progressData = workInfo.progress
        val outputData = workInfo.outputData
        var progress = 0f
        var progressText = ""
        var thumbnailUri: String? = null
        var totalItems = 1
        var fileName: String? = null
        var localPhotoId: String? = null

        // Support for dynamic filename and URI across all types
        when (type) {
            UploadType.Selective, UploadType.Instant -> {
                // Check both possible keys for URIs (Instant uses KEY_PHOTO_URI, Periodic uses KEY_CURRENT_FILE_URI)
                // Also fall back to WorkPhotoRegistry for queued workers that haven't run yet
                val extractedUri = progressData.getString(InstantPhotoUploadWorker.KEY_PHOTO_URI)?.takeIf<String> { it.isNotEmpty() }
                    ?: progressData.getString(PeriodicPhotoBackupWorker.KEY_CURRENT_FILE_URI)?.takeIf<String> { it.isNotEmpty() }
                    ?: outputData.getString(InstantPhotoUploadWorker.KEY_PHOTO_URI)?.takeIf<String> { it.isNotEmpty() }
                    ?: outputData.getString(PeriodicPhotoBackupWorker.KEY_CURRENT_FILE_URI)?.takeIf<String> { it.isNotEmpty() }
                    ?: WorkModule.WorkPhotoRegistry.resolve(workInfo.id.toString())?.takeIf<String> { it.isNotEmpty() }
                
                thumbnailUri = extractedUri
                localPhotoId = extractedUri
                
                val registryFileName = extractedUri?.substringAfterLast("/")?.takeIf { it.isNotEmpty() }
                progressText = when (status) {
                    UploadStatus.InProgress -> if (type == UploadType.Instant) "Syncing..." else "Uploading..."
                    UploadStatus.Completed -> "Synced"
                    UploadStatus.Failed -> "Failed — will retry"
                    else -> "Queued..."
                }
                
                fileName = progressData.getString(InstantPhotoUploadWorker.KEY_FILE_NAME)?.takeIf<String> { it.isNotEmpty() }
                    ?: outputData.getString(InstantPhotoUploadWorker.KEY_FILE_NAME)?.takeIf<String> { it.isNotEmpty() }
                    ?: registryFileName
                    ?: "Photo Upload"

                // Try to resolve thumbnail from localMap by filename if URI is missing
                if (thumbnailUri == null && fileName != null) {
                    thumbnailUri = localMap.values.find { it.substringAfterLast("/") == fileName || it.endsWith(fileName!!) }
                }
            }
            UploadType.Background -> {
                val extractedUri = progressData.getString(PeriodicPhotoBackupWorker.KEY_CURRENT_FILE_URI)?.takeIf<String> { it.isNotEmpty() }
                    ?: outputData.getString(PeriodicPhotoBackupWorker.KEY_CURRENT_FILE_URI)?.takeIf<String> { it.isNotEmpty() }
                
                thumbnailUri = extractedUri
                localPhotoId = extractedUri
                
                progressText = when (status) {
                    UploadStatus.InProgress -> {
                        val current = progressData.getInt(PeriodicPhotoBackupWorker.KEY_PROGRESS_CURRENT, 0)
                        val total = progressData.getInt(PeriodicPhotoBackupWorker.KEY_PROGRESS_MAX, 0)
                        if (total > 0) "Backing up $current of $total..." else "Calculating..."
                    }
                    UploadStatus.Completed -> "Backup complete"
                    UploadStatus.Failed -> "Backup failed"
                    else -> "Queued for backup..."
                }
                
                fileName = progressData.getString("fileName")?.takeIf<String> { it.isNotEmpty() }
                    ?: (if (type == UploadType.Selective) "User Choice" else "Auto Backup")
                
                // Try to resolve thumbnail from localMap by filename if currentUri is missing
                if (thumbnailUri == null) {
                    thumbnailUri = localMap.values.find { it.substringAfterLast("/") == fileName || it.endsWith(fileName!!) }
                }
            }
        }

        // Calculate Speed & ETA if in progress
        var speedText: String? = null
        var etaText: String? = null
        
        if (status == UploadStatus.InProgress && totalItems > 1 && progress > 0) {
            val startTime = workStartTimes.getOrPut(workInfo.id) { System.currentTimeMillis() }
            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1000L).toFloat() / 1000f
            val itemsDone = (progress * totalItems).coerceAtLeast(0.1f)
            val itemsPerSecond = itemsDone / elapsed
            
            if (itemsPerSecond > 0) {
                speedText = String.format("%.1f items/s", itemsPerSecond)
                val remainingItems = (totalItems - itemsDone).coerceAtLeast(0f)
                val remainingSeconds = (remainingItems / itemsPerSecond).roundToInt()
                
                etaText = if (remainingSeconds < 60) {
                    "$remainingSeconds s left"
                } else if (remainingSeconds < 3600) {
                    "${remainingSeconds / 60} m left"
                } else {
                    "${remainingSeconds / 3600} h left"
                }
            }
        } else if (status != UploadStatus.InProgress) {
            workStartTimes.remove(workInfo.id)
        }

        return UploadUiItem(
            id = workInfo.id.toString(),
            type = type,
            status = status,
            progress = progress,
            progressText = progressText,
            thumbnailUri = thumbnailUri,
            totalItems = totalItems,
            fileName = fileName,
            isCancellable = status == UploadStatus.InProgress || status == UploadStatus.Queued,
            localPhotoId = localPhotoId,
            speed = speedText,
            eta = etaText
        )
    }

    fun cancelUpload(id: String) {
        scope.launch {
            val queueId = id.toLongOrNull()
            if (queueId != null) {
                UploadQueueRepository.cancelQueueItem(queueId)
            }
            try {
                workManager?.cancelWorkById(UUID.fromString(id))
            } catch (e: Exception) {
                Log.e("ManageUploadsVM", "Error canceling work", e)
            }
        }
    }

    fun deleteHistoryItem(id: String, onUndoAvailable: (String) -> Unit) {
        scope.launch {
            Log.d("ManageUploadsVM", "Deleting history item: $id")
            // Temporarily store it for undo
            recentlyDeletedItem = DbHolder.database.remotePhotoDao().getById(id)
            DbHolder.database.remotePhotoDao().delete(id)
            onUndoAvailable("Item removed from history")
            
            // Auto-clear undo state after 5 seconds
            undoJob?.cancel()
            undoJob = launch {
                delay(5000)
                recentlyDeletedItem = null
            }
        }
    }

    fun undoDelete() {
        scope.launch {
            recentlyDeletedItem?.let { item ->
                Log.d("ManageUploadsVM", "Restoring history item: ${item.remoteId}")
                DbHolder.database.remotePhotoDao().insertAll(item)
                recentlyDeletedItem = null
            }
        }
    }

    fun clearAllHistory() {
        val currentSynced = syncedUploads.value.map { it.id }.toSet()
        val newDismissed = _dismissedIds.value + currentSynced
        _dismissedIds.value = newDismissed
        Preferences.edit { putStringSet("dismissedSyncedIds", newDismissed) }
    }

    fun retryAllFailed() {
        scope.launch {
            val count = UploadQueueRepository.retryAllEligible()
            if (count > 0) {
                WorkModule.PeriodicBackup.enqueue()
            }
        }
    }

    fun retryUpload(id: String) {
        scope.launch {
            val queueId = id.toLongOrNull()
            if (queueId != null) {
                val item = DbHolder.database.uploadQueueDao().getById(queueId)
                if (item != null && item.status !in listOf("EXCEEDS_LIMIT", "SOURCE_MISSING")) {
                    UploadQueueRepository.retryQueueItem(queueId)
                    WorkModule.PeriodicBackup.enqueue()
                    return@launch
                }
            }

            // Mark as retrying immediately — shows "Retrying..." in UI
            _retryingIds.value = _retryingIds.value + id
            _dismissedWorkIds.value = _dismissedWorkIds.value - id

            val item = allUploads.value.find { it.id == id }
            if (item != null) {
                // Try to enqueue using localPhotoId or thumbnailUri if available
                var uriStr = item.localPhotoId ?: (item.thumbnailUri as? String)

                // If URI not in work data, look up from DB by filename
                if (uriStr.isNullOrEmpty() && item.fileName != null) {
                    val photo = DbHolder.database.photoDao().getAllNotUploaded()
                        .find { it.pathUri.contains(item.fileName, ignoreCase = true) }
                    uriStr = photo?.pathUri
                }

                if (!uriStr.isNullOrEmpty()) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        WorkModule.InstantUpload(uri, type = "manual_backup").enqueue()
                    } catch (e: Exception) {
                        // couldn't retry
                    }
                }
            }
        }
    }

    fun dismissFailedItem(id: String) {
        _dismissedWorkIds.value = _dismissedWorkIds.value + id
    }

    fun refreshWorkInfo() {
        scope.launch {
            _isRefreshing.value = true
            // Simulate refresh delay to show the indicator for a short moment, 
            // as WorkInfo streams are continuous.
            delay(800)
            _isRefreshing.value = false
        }
    }

    // Second init block — runs after all property initializers (including rawWorkFlow)
    init {
        scope.launch {
            rawWorkFlow.collect { workItems ->
                val currentRetrying = _retryingIds.value
                if (currentRetrying.isEmpty()) return@collect
                val toClear = currentRetrying.filter { retryId ->
                    val retryItem = workItems.find { it.id == retryId } ?: return@filter false
                    val fileName = retryItem.fileName ?: return@filter false
                    workItems.any { item ->
                        item.id != retryId &&
                        item.fileName == fileName &&
                        item.id !in currentRetrying &&
                        item.status != UploadStatus.Failed
                    }
                }
                if (toClear.isNotEmpty()) {
                    _retryingIds.value = currentRetrying - toClear.toSet()
                    _dismissedWorkIds.value = _dismissedWorkIds.value + toClear.toSet()
                }
            }
        }
    }
}
