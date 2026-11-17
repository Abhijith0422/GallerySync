package com.gallerysync.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import com.gallerysync.app.data.localdb.entities.VideoRestorationJob
import com.gallerysync.app.data.restoration.RestorationStatus
import com.gallerysync.app.data.restoration.VideoRestorationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.animation.*
import com.gallerysync.app.R
import com.gallerysync.app.data.localdb.DbHolder
import com.gallerysync.app.data.localdb.entities.Photo
import com.gallerysync.app.player.LocalMediaUriValidator
import com.gallerysync.app.player.Media3VideoPlayer
import com.gallerysync.app.player.VideoPlaybackCoordinator
import com.gallerysync.app.player.VideoPlayerView
import com.gallerysync.app.ui.main.screens.local.UploadState
import com.gallerysync.app.ui.main.screens.remote.DownloadState
import com.gallerysync.app.utils.coil.ImageLoaderModule
import com.gallerysync.app.utils.toastFromMainThread
import com.gallerysync.app.workers.WorkModule
import com.gallerysync.app.workers.WorkModule.UPLOADING_ID
import com.gallerysync.app.workers.WorkModule.DOWNLOADING_ID
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import android.view.Window

@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhotoView(
    photo: Photo,
    isOnlyRemote: Boolean,
    showUiState: () -> MutableState<Boolean>,
    window: Window,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isCurrentPage: Boolean = true
) {
    val context = LocalContext.current
    var showUi by showUiState()
    val scope = rememberCoroutineScope()
    val isOnlyOnDevice = rememberSaveable { photo.remoteId == null }
    var photoUploadState by rememberSaveable {
        mutableStateOf(
            if (isOnlyOnDevice) UploadState.NOT_UPLOADED else UploadState.UPLOADED
        )
    }
    
    // Download state for remote photos
    val isAlreadyOnDevice = rememberSaveable { photo.remoteId != null && !isOnlyRemote }
    var photoDownloadState by rememberSaveable {
        mutableStateOf(
            if (isAlreadyOnDevice) DownloadState.DOWNLOADED else DownloadState.NOT_DOWNLOADED
        )
    }
    
    // Dialog state for download confirmation when photo exists
    var showDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var existingPhotoPath by rememberSaveable { mutableStateOf("") }

    // Media type and local URI resolution for video playback
    val isVideo = photo.mediaType.equals("VIDEO", ignoreCase = true)

    // Video restoration state for cloud videos (Phase 10.5)
    var activeRestorationJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeRestorationJob by remember { mutableStateOf<VideoRestorationJob?>(null) }
    var isDirectOverHostedLimit by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(photo.localId, photo.remoteId, activeRestorationJobId) {
        val backupId = if (photo.remoteId?.startsWith("CHUNKED_") == true) {
            photo.remoteId.removePrefix("CHUNKED_")
        } else {
            photo.localId.takeIf { it.isNotBlank() }
        }
        val (existingJob, overLimit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dao = DbHolder.database.videoRestorationDao()
            val job = if (activeRestorationJobId != null) {
                dao.getById(activeRestorationJobId!!)
            } else if (!backupId.isNullOrBlank()) {
                dao.getByBackupId(backupId)
            } else if (photo.remoteId != null) {
                dao.getByRemoteId(photo.remoteId)
            } else {
                null
            }

            val chunkedBackup = backupId?.let { DbHolder.database.chunkedBackupDao().getById(it) }
            val remotePhoto = photo.remoteId?.let { DbHolder.database.remotePhotoDao().getById(it) }
            val isChunked = chunkedBackup != null || photo.remoteId?.startsWith("CHUNKED_") == true
            val size = chunkedBackup?.fileSize ?: remotePhoto?.fileSize ?: 0L
            val isOver = isVideo && !isChunked && size > com.gallerysync.app.data.restoration.RestorationConfiguration.MAX_HOSTED_TELEGRAM_DOWNLOAD_BYTES
            Pair(job, isOver)
        }
        isDirectOverHostedLimit = overLimit
        if (existingJob != null && existingJob.status != RestorationStatus.CANCELLED) {
            activeRestorationJobId = existingJob.restorationId
            activeRestorationJob = existingJob
        }
    }

    androidx.compose.runtime.LaunchedEffect(activeRestorationJobId) {
        activeRestorationJobId?.let { id ->
            DbHolder.database.videoRestorationDao().observeById(id).collect { job ->
                activeRestorationJob = job
            }
        }
    }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val backupId = if (photo.remoteId?.startsWith("CHUNKED_") == true) {
                    photo.remoteId.removePrefix("CHUNKED_")
                } else {
                    photo.localId.takeIf { it.isNotBlank() }
                }

                val chunkedBackup = backupId?.let { DbHolder.database.chunkedBackupDao().getById(it) }
                val remotePhoto = photo.remoteId?.let { DbHolder.database.remotePhotoDao().getById(it) }

                val fileName = chunkedBackup?.fileName ?: remotePhoto?.fileName
                    ?: "video_${photo.localId.takeIf { it.isNotBlank() } ?: System.currentTimeMillis()}.mp4"
                val totalChunks = chunkedBackup?.totalChunks ?: 1
                val fileSize = chunkedBackup?.fileSize ?: remotePhoto?.fileSize ?: 0L
                val fileSha256 = chunkedBackup?.fileSha256 ?: remotePhoto?.contentHash ?: photo.contentHash ?: ""

                val job = VideoRestorationManager.defaultInstance.createOrGetJob(
                    context = context,
                    backupId = chunkedBackup?.backupId,
                    remoteId = if (chunkedBackup == null) photo.remoteId else null,
                    localId = photo.localId,
                    fileName = fileName,
                    mimeType = "video/mp4",
                    fileSize = fileSize,
                    fileSha256 = fileSha256,
                    totalChunks = totalChunks,
                    destinationUri = uri.toString()
                )

                WorkModule.VideoRestoration(job.restorationId).enqueue()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    activeRestorationJobId = job.restorationId
                }
            }
        }
    }

    val directUri = remember(photo.pathUri) {
        if (LocalMediaUriValidator.isLocalMediaUri(photo.pathUri)) photo.pathUri else null
    }
    var localPlaybackUriString by remember(photo.localId, photo.remoteId, photo.pathUri) {
        mutableStateOf(directUri)
    }

    androidx.compose.runtime.LaunchedEffect(photo.remoteId) {
        if (localPlaybackUriString == null && photo.remoteId != null) {
            val localMatch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DbHolder.database.photoDao().getByRemoteId(photo.remoteId)
            }
            if (localMatch != null && LocalMediaUriValidator.isLocalMediaUri(localMatch.pathUri)) {
                localPlaybackUriString = localMatch.pathUri
            }
        }
    }

    val playerKey = remember(photo.localId, photo.remoteId, photo.pathUri) {
        photo.localId.takeIf { it.isNotBlank() } ?: (photo.remoteId ?: photo.pathUri)
    }

    val coordinator = remember(playerKey, localPlaybackUriString) {
        if (isVideo && localPlaybackUriString != null) {
            VideoPlaybackCoordinator {
                Media3VideoPlayer(context.applicationContext, localPlaybackUriString!!.toUri())
            }
        } else {
            null
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(coordinator, lifecycleOwner) {
        if (coordinator != null) {
            lifecycleOwner.lifecycle.addObserver(coordinator)
        }
        onDispose {
            if (coordinator != null) {
                lifecycleOwner.lifecycle.removeObserver(coordinator)
                coordinator.release()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(coordinator, isCurrentPage) {
        coordinator?.onPageSelected(isCurrentPage)
    }


    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(Unit) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = true
            }
        }
    }
    
    // Helper function to check if photo exists and handle download
    suspend fun handleDownloadClick(remoteId: String, forceDownload: Boolean = false) {
        android.util.Log.d("PhotoView", "Checking if photo exists for remoteId: $remoteId")
        val existingPhoto = DbHolder.database.photoDao().getByRemoteId(remoteId)
        
        if (existingPhoto != null && !forceDownload) {
            android.util.Log.d("PhotoView", "Photo exists at: ${existingPhoto.pathUri}")
            existingPhotoPath = existingPhoto.pathUri
            showDownloadDialog = true
        } else {
            android.util.Log.d("PhotoView", "Starting download for remoteId: $remoteId")
            WorkModule.InstantDownload(remoteId, forceDownload).enqueue()
            photoDownloadState = DownloadState.ENQUEUED
        }
    }

    // Helper function to handle upload
    fun handleUploadClick() {
        android.util.Log.d("PhotoView", "Starting upload for photo: ${photo.localId}")
        WorkModule.InstantUpload(photo.pathUri.toUri(), type = "manual_backup").enqueue()
        photoUploadState = UploadState.ENQUEUED
    }

    // Observe download worker
    androidx.compose.runtime.LaunchedEffect(photo.remoteId) {
        photo.remoteId?.let { remoteId ->
            WorkModule.observeWorkerByName("${WorkModule.DOWNLOADING_ID}:$remoteId").collectLatest { workInfoList ->
                workInfoList.firstOrNull()?.let { workInfo ->
                    android.util.Log.d("PhotoView", "Download worker state changed: ${workInfo.state}")
                    photoDownloadState = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> DownloadState.ENQUEUED
                        WorkInfo.State.RUNNING -> DownloadState.DOWNLOADING
                        WorkInfo.State.SUCCEEDED -> DownloadState.DOWNLOADED
                        WorkInfo.State.FAILED -> DownloadState.FAILED
                        WorkInfo.State.BLOCKED -> DownloadState.BLOCKED
                        WorkInfo.State.CANCELLED -> DownloadState.FAILED
                    }
                }
            }
        }
    }

    // Observe upload worker
    androidx.compose.runtime.LaunchedEffect(photo.pathUri) {
        if (isOnlyOnDevice) {
            val uniqueWorkName = "${WorkModule.UPLOADING_ID}:${photo.pathUri.toUri().lastPathSegment}"
            WorkModule.observeWorkerByName(uniqueWorkName).collectLatest { workInfoList ->
                workInfoList.firstOrNull()?.let { workInfo ->
                    android.util.Log.d("PhotoView", "Upload worker state changed: ${workInfo.state}")
                    photoUploadState = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> UploadState.ENQUEUED
                        WorkInfo.State.RUNNING -> UploadState.UPLOADING
                        WorkInfo.State.SUCCEEDED -> UploadState.UPLOADED
                        WorkInfo.State.FAILED -> UploadState.FAILED
                        WorkInfo.State.BLOCKED -> UploadState.BLOCKED
                        WorkInfo.State.CANCELLED -> UploadState.FAILED
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVideo) {
            val activePlayer = coordinator?.player
            if (localPlaybackUriString != null && activePlayer != null) {
                VideoPlayerView(
                    player = activePlayer,
                    modifier = Modifier.fillMaxSize(),
                    enableControls = true,
                    onSurfaceClick = { showUi = !showUi }
                )
            } else if (localPlaybackUriString != null) {
                // Adjacent precomposed page or initializing player: show static frame with play badge
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showUi = !showUi }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    with(sharedTransitionScope) {
                        SubcomposeAsyncImage(
                            imageLoader = ImageLoaderModule.defaultImageLoader,
                            model = ImageRequest.Builder(context)
                                .data(localPlaybackUriString)
                                .crossfade(200)
                                .build(),
                            contentDescription = "Video Thumbnail",
                            contentScale = ContentScale.Fit,
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.ContainedLoadingIndicator()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            } else {
                // Remote video without local copy: display thumbnail preview, video badge, and explanatory message
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showUi = !showUi }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    with(sharedTransitionScope) {
                        SubcomposeAsyncImage(
                            imageLoader = ImageLoaderModule.remoteImageLoader,
                            model = ImageRequest.Builder(context)
                                .data(
                                    if (photo.previewRemoteId != null) com.gallerysync.app.utils.coil.FileIdData(photo.previewRemoteId)
                                    else if (photo.remoteId != null) com.gallerysync.app.utils.coil.FileIdData(photo.remoteId)
                                    else photo.pathUri
                                )
                                .memoryCacheKey(if (photo.remoteId != null) "fid_${photo.remoteId}_full" else null)
                                .diskCacheKey(if (photo.remoteId != null) "fid_${photo.remoteId}" else null)
                                .crossfade(300)
                                .build(),
                            contentDescription = "Cloud Video",
                            contentScale = ContentScale.Fit,
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.ContainedLoadingIndicator()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 90.dp)
                            .align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "Video stored in Cloud",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }

                            val currentJob = activeRestorationJob
                            if (currentJob != null && currentJob.status.isActive) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val progressFloat = if (currentJob.expectedFileSize > 0L) {
                                        (currentJob.downloadedBytes.toFloat() / currentJob.expectedFileSize.toFloat()).coerceIn(0f, 1f)
                                    } else 0f

                                    LinearProgressIndicator(
                                        progress = { progressFloat },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = currentJob.status.userFriendlyDescription(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )
                                        Text(
                                            text = "${(progressFloat * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )
                                    }

                                    if (currentJob.totalChunks > 1) {
                                        Text(
                                            text = "Chunks: ${currentJob.verifiedChunks} / ${currentJob.totalChunks}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                WorkModule.VideoRestoration(currentJob.restorationId).cancel()
                                                DbHolder.database.videoRestorationDao().updateStatus(currentJob.restorationId, RestorationStatus.CANCELLED)
                                            }
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            } else if (currentJob != null && currentJob.status == RestorationStatus.COMPLETED) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Restoration Complete! Saved to device.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Green
                                    )
                                    Button(
                                        onClick = {
                                            currentJob.destinationUri?.let { destUriStr ->
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(destUriStr.toUri(), "video/*")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    android.util.Log.e("PhotoView", "Failed opening restored video: ${e.message}")
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open Restored Video")
                                    }
                                }
                            } else if (isDirectOverHostedLimit) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Rounded.Error,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Restoration Unavailable (> 20 MB)",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Direct video exceeds Telegram's hosted 20 MB download limit (getFile ceiling). It cannot be retrieved via api.telegram.org.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val suggestedName = "restored_video_${photo.localId.takeIf { it.isNotBlank() } ?: System.currentTimeMillis()}.mp4"
                                        safLauncher.launch(suggestedName)
                                    }
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Restore Video to Device")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val effectiveLocalPath = localPlaybackUriString ?: photo.pathUri.takeIf { it.isNotBlank() && LocalMediaUriValidator.isLocalMediaUri(it) }
            val thumbFileId = photo.previewRemoteId ?: photo.remoteId

            with(sharedTransitionScope) {
                Box(
                    modifier = Modifier
                        .sharedElement(
                            rememberSharedContentState(key = if (isOnlyRemote) "photo_${photo.remoteId}" else "photo_${photo.localId}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> com.gallerysync.app.ui.theme.AnimationConstants.PremiumBoundsSpring }
                        )
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showUi = !showUi }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (effectiveLocalPath != null) {
                        // Local image: instant high-res render from device storage
                        AsyncImage(
                            imageLoader = ImageLoaderModule.defaultImageLoader,
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(effectiveLocalPath.toUri())
                                .crossfade(200)
                                .build(),
                            contentDescription = "Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (photo.remoteId != null) {
                        // Two-Tier Cloud Image Loading:
                        // Tier 1: Immediately render low-res thumbnail from disk/memory cache
                        if (thumbFileId != null) {
                            AsyncImage(
                                imageLoader = ImageLoaderModule.thumbnailImageLoader,
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(com.gallerysync.app.utils.coil.FileIdData(thumbFileId))
                                    .memoryCacheKey("fid_${thumbFileId}_180")
                                    .diskCacheKey("fid_$thumbFileId")
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Tier 2: Stream full-resolution original from Telegram in background and crossfade
                        var isFullLoaded by remember(photo.remoteId) { mutableStateOf(false) }

                        AsyncImage(
                            imageLoader = ImageLoaderModule.remoteImageLoader,
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(com.gallerysync.app.utils.coil.FileIdData(photo.remoteId))
                                .memoryCacheKey("fid_${photo.remoteId}_full")
                                .diskCacheKey("fid_${photo.remoteId}")
                                .crossfade(400)
                                .listener(
                                    onSuccess = { _, _ -> isFullLoaded = true },
                                    onError = { _, _ -> isFullLoaded = true }
                                )
                                .build(),
                            contentDescription = "Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Subtle progress indicator while high-res original streams in background
                        if (!isFullLoaded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 100.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Download button for cloud photos
        AnimatedVisibility(
            visible = isOnlyRemote && showUi,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            FloatingDownloadBar(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                downloadState = photoDownloadState,
                onClickDownload = {
                    android.util.Log.d("PhotoView", "Download/Restore button clicked!")
                    if (isVideo) {
                        val suggestedName = "restored_video_${photo.localId.takeIf { it.isNotBlank() } ?: System.currentTimeMillis()}.mp4"
                        safLauncher.launch(suggestedName)
                    } else {
                        photo.remoteId?.let { remoteId ->
                            scope.launch {
                                handleDownloadClick(remoteId)
                            }
                        } ?: android.util.Log.e("PhotoView", "No remoteId found for photo!")
                    }
                }
            )
        }

        // Upload button for local photos
        AnimatedVisibility(
            visible = !isOnlyRemote && showUi,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            FloatingBottomBar(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                uploadState = photoUploadState,
                onClickUpload = {
                    handleUploadClick()
                }
            )
        }
    }
    
    // Download confirmation dialog when photo already exists
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Photo Already Downloaded") },
            text = { 
                Text("This photo is already downloaded to your device at:\n\n$existingPhotoPath\n\nWhat would you like to do?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDownloadDialog = false
                        photo.remoteId?.let { remoteId ->
                            scope.launch {
                                handleDownloadClick(remoteId, forceDownload = true)
                            }
                        }
                    }
                ) {
                    Text("Download Anyway")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDownloadDialog = false
                        // Try to open in gallery
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(existingPhotoPath.toUri(), "image/*")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("PhotoView", "Failed to open in gallery: ${e.message}")
                            scope.launch {
                                context.toastFromMainThread("Unable to open in gallery")
                            }
                        }
                    }
                ) {
                    Text("View in Gallery")
                }
            }
        )
    }
}
