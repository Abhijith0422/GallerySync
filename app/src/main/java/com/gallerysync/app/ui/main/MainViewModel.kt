package com.gallerysync.app.ui.main

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.WhDatabase
import com.gallerysync.app.data.localdb.entities.ChunkedBackupStatus
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.data.localdb.entities.RemotePhoto
import com.gallerysync.app.data.localdb.recovery.InstallRecoveryManager
import com.gallerysync.app.data.mediastore.AlbumInfo
import com.gallerysync.app.data.mediastore.LocalPhotoSource
import com.gallerysync.app.data.mediastore.MediaStoreScanner
import com.gallerysync.app.utils.PermissionManager
import com.gallerysync.app.utils.getExtFromMimeType
import kotlinx.coroutines.flow.flatMapLatest
import com.gallerysync.app.data.mediastore.LocalUiPhoto
import com.gallerysync.app.ui.main.nav.Screens
import com.gallerysync.app.workers.WorkModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var currentDestination by mutableStateOf<Screens>(Screens.LocalPhotos)
        private set

    fun updateDestination(destination: Screens) {
        currentDestination = destination
    }

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun updateSyncState(newState: SyncState) {
        _syncState.value = newState
    }

    // Upload/Backup state tracking
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    init {
        // Combine observations of manual_backup and instant_upload tags
        viewModelScope.launch {
            try {
                combine(
                    WorkModule.observeWorkerByTag("manual_backup"),
                    WorkModule.observeWorkerByTag("instant_upload"),
                    WorkModule.observeWorkerByName("InstantPhotoBackupWork"),
                    WorkModule.observeWorkerByName(WorkModule.PERIODIC_PHOTO_BACKUP_WORK)
                ) { manualWorkList, instantWorkList, instantPeriodicList, periodicWorkList ->
                    val manualActive = manualWorkList.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    val instantActive = instantWorkList.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    val instantPeriodicActive = instantPeriodicList.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    
                    // periodicWork is always ENQUEUED, so we only check for RUNNING state
                    val periodicActive = periodicWorkList.any { it.state == WorkInfo.State.RUNNING }
                    
                    manualActive || instantActive || instantPeriodicActive || periodicActive
                }.collect { isActive ->
                    _isUploading.value = isActive
                    Log.d("MainViewModel", "Upload state: $isActive")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error observing upload work", e)
            }
        }
        loadMediaStorePhotos()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                reconcileLocalRemotePhotos(DbHolder.database)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reconciling remote photos", e)
            }
        }
    }

    val localPhotosFlow: Flow<PagingData<LocalUiPhoto>> by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 192,
                jumpThreshold = 160,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                Log.d("MainViewModel", "=== CREATING NEW LOCAL PAGING SOURCE ===")
                LocalPhotoSource(getApplication())
            }
        ).flow.cachedIn(viewModelScope)
    }

    // ── Topic/album filtering for remote grid ──

    private val _selectedTopicAlbumId = MutableStateFlow(-1L)  // -1L = "All"
    val selectedTopicAlbumId: StateFlow<Long> = _selectedTopicAlbumId.asStateFlow()

    fun selectTopicAlbum(id: Long) {
        _selectedTopicAlbumId.value = id
    }

    val topicAlbums: StateFlow<List<AlbumInfo>> by lazy {
        combine(
            DbHolder.database.remotePhotoDao().getDistinctTopicNames(),
            totalCloudPhotosCount
        ) { names, totalCount ->
            mutableListOf(
                AlbumInfo(id = -1L, label = "All", count = totalCount, coverUri = "")
            ).apply {
                names.forEachIndexed { index, name ->
                    add(AlbumInfo(id = index.toLong(), label = name, count = 0, coverUri = ""))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allCloudPhotosFlow: Flow<PagingData<RemotePhoto>> = _selectedTopicAlbumId.flatMapLatest { albumId ->
        Pager(
            config = PagingConfig(
                pageSize = 24,
                prefetchDistance = 72,
                jumpThreshold = 120
            ),
            pagingSourceFactory = {
                if (albumId == -1L) {
                    Log.d("MainViewModel", "=== CREATING ALL REMOTE PAGING SOURCE ===")
                    DbHolder.database.remotePhotoDao().getAllPagingSource()
                } else {
                    val currentAlbums = topicAlbums.value
                    val topicName = currentAlbums.find { it.id == albumId }?.label
                    if (topicName != null && topicName != "All") {
                        Log.d("MainViewModel", "=== CREATING TOPIC PAGING SOURCE: $topicName ===")
                        DbHolder.database.remotePhotoDao().getByTopicNamePagingSource(topicName)
                    } else {
                        DbHolder.database.remotePhotoDao().getAllPagingSource()
                    }
                }
            }
        ).flow
    }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allCloudPhotosList: StateFlow<List<RemotePhoto>> by lazy {
        _selectedTopicAlbumId.flatMapLatest { albumId ->
            if (albumId == -1L) {
                DbHolder.database.remotePhotoDao().getAllFlow()
            } else {
                val currentAlbums = topicAlbums.value
                val topicName = currentAlbums.find { it.id == albumId }?.label
                if (topicName != null && topicName != "All") {
                    DbHolder.database.remotePhotoDao().getByTopicNameFlow(topicName)
                } else {
                    DbHolder.database.remotePhotoDao().getAllFlow()
                }
            }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    val localPhotosCount: StateFlow<Int> by lazy {
        DbHolder.database.photoDao().getAllCountFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    }

    val totalCloudPhotosCount: StateFlow<Int> by lazy {
        DbHolder.database.remotePhotoDao().getTotalCountFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    }

    val pendingUploadsFlow: StateFlow<List<Photo>> by lazy {
        DbHolder.database.photoDao().getPendingUploadFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    val allLocalPhotos: StateFlow<List<com.gallerysync.app.data.localdb.entities.Photo>> by lazy {
        mediaStorePhotos.map { list ->
            list.map { uiPhoto ->
                com.gallerysync.app.data.localdb.entities.Photo(
                    localId = uiPhoto.localId,
                    remoteId = uiPhoto.remoteId,
                    photoType = getExtFromMimeType(uiPhoto.mimeType) ?: if (uiPhoto.isVideo) "mp4" else "jpg",
                    pathUri = uiPhoto.pathUri,
                    mediaType = uiPhoto.mediaType,
                    duration = uiPhoto.duration
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    val allRemotePhotos: StateFlow<List<com.gallerysync.app.data.localdb.entities.Photo>> by lazy {
        DbHolder.database.remotePhotoDao().getAllFlow()
            .map { list -> list.map { it.toPhoto() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    val localPhotoMap: StateFlow<Map<String, String>> by lazy {
        allLocalPhotos.map { list ->
            val map = HashMap<String, String>(list.size * 2)
            list.forEach { p ->
                if (p.localId.isNotBlank()) map[p.localId] = p.pathUri
                if (!p.remoteId.isNullOrBlank()) map[p.remoteId!!] = p.pathUri
            }
            map
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }
    // Track the last viewed photo ID to sync grid position on return
    private val _lastViewedPhotoId = MutableStateFlow<String?>(null)
    val lastViewedPhotoId: StateFlow<String?> = _lastViewedPhotoId.asStateFlow()

    fun updateLastViewedPhotoId(id: String) {
        _lastViewedPhotoId.value = id
    }

    // Explicitly save scroll state to handle exact restoration
    private var _clickedPhotoId: String? = null
    val clickedPhotoId: String?
        get() = _clickedPhotoId

    private var _savedScrollIndex: Int = 0
    val savedScrollIndex: Int
        get() = _savedScrollIndex

    private var _savedScrollOffset: Int = 0
    val savedScrollOffset: Int
        get() = _savedScrollOffset

    fun saveScrollState(photoId: String, index: Int, offset: Int) {
        _clickedPhotoId = photoId
        _savedScrollIndex = index
        _savedScrollOffset = offset
        Log.d("MainViewModel", "Saved scroll state: id=$photoId, index=$index, offset=$offset")
    }

    // Cache MediaStore photos for immediate grid access
    private val _mediaStorePhotos = MutableStateFlow<List<LocalUiPhoto>>(emptyList())
    val mediaStorePhotos: StateFlow<List<LocalUiPhoto>> = _mediaStorePhotos.asStateFlow()

    // Album chip bar state — derived from loaded photos
    private val _albums = MutableStateFlow<List<AlbumInfo>>(emptyList())
    val albums: StateFlow<List<AlbumInfo>> = _albums.asStateFlow()

    private val _selectedAlbumId = MutableStateFlow(-1L) // -1L = "All"
    val selectedAlbumId: StateFlow<Long> = _selectedAlbumId.asStateFlow()

    fun selectAlbum(id: Long) {
        _selectedAlbumId.value = id
    }

    // Filtered photos based on selected album
    val filteredMediaStorePhotos: StateFlow<List<LocalUiPhoto>> =
        combine(_mediaStorePhotos, _selectedAlbumId) { photos, albumId ->
            if (albumId == -1L) photos
            else photos.filter { it.bucketId == albumId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Pull-to-Refresh State ──
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    fun clearRefreshError() {
        _refreshError.value = null
    }

    /**
     * Executes an incremental, offline-safe pull-to-refresh of the local gallery and backup states.
     *
     * Guarantees:
     * - Never automatically releases or uploads HOLDING media.
     * - Discovers newly added images and videos (indexed as STATUS_NONE after baseline).
     * - Prunes deleted items from the local database.
     * - Refreshes local MediaStore UI list and album collections.
     * - Refreshes database backup statuses without forcing network uploads or triggering duplicate workers.
     * - Prevents concurrent refresh operations.
     */
    fun refreshGallery(onComplete: (() -> Unit)? = null) {
        if (_isRefreshing.value) {
            Log.d("MainViewModel", "Refresh already running, ignoring duplicate pull-to-refresh request")
            return
        }

        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshError.value = null
            val startTime = System.currentTimeMillis()

            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val photoDao = DbHolder.database.photoDao()

                    if (PermissionManager.hasAnyMediaAccess(context)) {
                        val isBaselineDone = InstallRecoveryManager.isBaselineCompleted(DbHolder.database)
                        val baselineTimestamp = InstallRecoveryManager.getBaselineTimestamp(DbHolder.database)

                        // 1. Unified single-pass MediaStore scan & UI cache population
                        val scannedPhotos = mutableListOf<LocalUiPhoto>()
                        MediaStoreScanner.scanAllInBatches(context, batchSize = 500) { batch ->
                            scannedPhotos.addAll(batch.map { it.toLocalUiPhoto() })

                            val photos = batch.map { item ->
                                val itemTimestampMs = maxOf(item.dateAdded, item.dateModified) * 1000L
                                val status = if (!isBaselineDone || (baselineTimestamp > 0L && itemTimestampMs <= baselineTimestamp)) {
                                    Photo.STATUS_HOLDING
                                } else {
                                    Photo.STATUS_NONE
                                }
                                item.toPhoto(defaultUploadStatus = status)
                            }
                            // Insert with IGNORE: preserves all existing HOLDING and DONE items!
                            photoDao.insertPhotos(*photos.toTypedArray())
                        }

                        // Fetch synced status from DB and update UI cache
                        val syncedMap = photoDao.getSyncedPhotoMap().associate { it.localId to it.remoteId }
                        val updatedPhotos = scannedPhotos.map { photo ->
                            val remoteId = syncedMap[photo.localId]
                            if (remoteId != null) photo.copy(remoteId = remoteId) else photo
                        }
                        _mediaStorePhotos.value = updatedPhotos

                        // Derive album list from loaded photos ("All" + per-bucket)
                        val bucketGroups = updatedPhotos.groupBy { it.bucketId }
                        val albumList = mutableListOf(
                            AlbumInfo(
                                id = -1L,
                                label = "All",
                                count = updatedPhotos.size,
                                coverUri = updatedPhotos.firstOrNull()?.pathUri ?: ""
                            )
                        )
                        bucketGroups.entries
                            .sortedByDescending { it.value.size }
                            .forEach { (bucketId, bucketPhotos) ->
                                val name = bucketPhotos.first().bucketName.ifEmpty { "Unknown" }
                                albumList.add(
                                    AlbumInfo(
                                        id = bucketId,
                                        label = name,
                                        count = bucketPhotos.size,
                                        coverUri = bucketPhotos.first().pathUri
                                    )
                                )
                            }
                        _albums.value = albumList

                        // 2. Safe and ultra-fast deletion pruning (< 50ms)
                        MediaStoreScanner.pruneDeletedMedia(context, photoDao)
                    } else {
                        Log.w("MainViewModel", "No media access permission granted during pull-to-refresh")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error executing gallery pull-to-refresh", e)
                _refreshError.value = "Failed to refresh gallery: ${e.message ?: "Unknown error"}"
            } finally {
                // Smooth indicator dismissal with minimum display threshold (400ms)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 400L) {
                    delay(400L - elapsed)
                }
                _isRefreshing.value = false
                onComplete?.invoke()
            }
        }
    }

    fun refreshMediaStorePhotos() {
        loadMediaStorePhotos()
    }

    private fun loadMediaStorePhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                loadMediaStorePhotosInternal(context)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading MediaStore photos", e)
            }
        }
    }

    private suspend fun loadMediaStorePhotosInternal(context: Context) {
        val scannedPhotos = mutableListOf<LocalUiPhoto>()
        MediaStoreScanner.scanAllInBatches(context, batchSize = 1000) { batch ->
            scannedPhotos.addAll(batch.map { it.toLocalUiPhoto() })
        }

        // Fetch synced status from DB
        val syncedMap = DbHolder.database.photoDao().getSyncedPhotoMap().associate { it.localId to it.remoteId }

        // Update remoteId for photos that are synced
        val updatedPhotos = scannedPhotos.map { photo ->
            val remoteId = syncedMap[photo.localId]
            if (remoteId != null) photo.copy(remoteId = remoteId) else photo
        }

        _mediaStorePhotos.value = updatedPhotos

        // Derive album list from loaded photos ("All" + per-bucket)
        val bucketGroups = updatedPhotos.groupBy { it.bucketId }
        val albumList = mutableListOf(
            AlbumInfo(
                id = -1L,
                label = "All",
                count = updatedPhotos.size,
                coverUri = updatedPhotos.firstOrNull()?.pathUri ?: ""
            )
        )
        bucketGroups.entries
            .sortedByDescending { it.value.size }
            .forEach { (bucketId, bucketPhotos) ->
                val name = bucketPhotos.first().bucketName.ifEmpty { "Unknown" }
                albumList.add(
                    AlbumInfo(
                        id = bucketId,
                        label = name,
                        count = bucketPhotos.size,
                        coverUri = bucketPhotos.first().pathUri
                    )
                )
            }
        _albums.value = albumList
    }

    private suspend fun reconcileLocalRemotePhotos(database: WhDatabase) {
        val remoteDao = database.remotePhotoDao()
        val chunkDao = database.chunkedBackupDao()
        val photoDao = database.photoDao()

        val existingRemoteIds = remoteDao.getAllRemoteIds().toSet()

        // 1. Backfill from completed chunked backups
        val doneChunked = chunkDao.getAll().filter { it.status == ChunkedBackupStatus.DONE && it.manifestFileId != null }
        val missingFromChunked = doneChunked.filter { it.manifestFileId !in existingRemoteIds }.map { backup ->
            RemotePhoto(
                remoteId = backup.manifestFileId!!,
                photoType = "mp4",
                fileName = backup.fileName,
                fileSize = backup.fileSize,
                uploadedAt = backup.completedAt ?: backup.createdAt,
                thumbnailCached = false,
                localId = backup.localId,
                messageId = backup.manifestMessageId,
                contentHash = backup.fileSha256,
                mediaType = "VIDEO",
                status = "ACTIVE",
                width = backup.width,
                height = backup.height,
                duration = backup.duration
            )
        }
        if (missingFromChunked.isNotEmpty()) {
            remoteDao.insertAllIfNotExists(*missingFromChunked.toTypedArray())
            Log.i("MainViewModel", "Reconciled ${missingFromChunked.size} chunked video records into remote_photos")
        }

        // 2. Backfill from photos with remoteId
        val uploadedPhotos = photoDao.getAllUploaded()
        val missingFromPhotos = uploadedPhotos.filter { 
            val rid = it.remoteId
            rid != null && !rid.startsWith("CHUNKED_") && rid !in existingRemoteIds 
        }.map { photo ->
            RemotePhoto(
                remoteId = photo.remoteId!!,
                photoType = photo.photoType,
                fileName = photo.pathUri.substringAfterLast("/"),
                fileSize = null,
                uploadedAt = photo.lastUploadAttempt ?: System.currentTimeMillis(),
                thumbnailCached = false,
                localId = photo.localId,
                contentHash = photo.contentHash,
                mediaType = photo.mediaType,
                status = "ACTIVE",
                width = photo.width,
                height = photo.height,
                duration = photo.duration
            )
        }
        if (missingFromPhotos.isNotEmpty()) {
            remoteDao.insertAllIfNotExists(*missingFromPhotos.toTypedArray())
            Log.i("MainViewModel", "Reconciled ${missingFromPhotos.size} uploaded photo records into remote_photos")
        }
    }
}
