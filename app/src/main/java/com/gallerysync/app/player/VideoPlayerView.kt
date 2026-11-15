package com.gallerysync.app.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Reusable Compose video player component backed by [GalleryVideoPlayer].
 *
 * Renders the video surface using Media3 [PlayerView] inside an [AndroidView]
 * and provides custom Material 3 controls for Play/Pause, seek scrubbing,
 * time display, buffering indicator, and error recovery.
 *
 * Note: Player lifecycle and ownership belong to the caller. This composable
 * attaches and detaches the player from [PlayerView] but does NOT release it on disposal.
 */
@Composable
fun VideoPlayerView(
    player: GalleryVideoPlayer,
    modifier: Modifier = Modifier,
    enableControls: Boolean = true,
    onRetry: (() -> Unit)? = null,
    onSurfaceClick: (() -> Unit)? = null
) {
    val isPlaying by player.isPlaying.collectAsState()
    val playbackState by player.playbackState.collectAsState()
    val errorMessage by player.error.collectAsState()

    var areControlsVisible by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(player.currentPosition) }
    var totalDurationMs by remember { mutableLongStateOf(player.duration) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    // Periodically update playback position while playing
    LaunchedEffect(isPlaying, isScrubbing) {
        if (!isScrubbing) {
            currentPositionMs = player.currentPosition
            val d = player.duration
            if (d > 0L) totalDurationMs = d
        }
        while (isPlaying && !isScrubbing) {
            delay(200)
            currentPositionMs = player.currentPosition
            val d = player.duration
            if (d > 0L) totalDurationMs = d
        }
    }

    // Auto-hide controls after inactivity while actively playing
    LaunchedEffect(areControlsVisible, isPlaying, isScrubbing) {
        if (areControlsVisible && isPlaying && !isScrubbing) {
            delay(3500)
            areControlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .fillMaxSize()
            .testTag("video_player_container")
    ) {
        // 1. AndroidView hosting Media3 PlayerView
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.player = player.player
                }
            },
            update = { playerView ->
                val target = player.player
                if (playerView.player != target) {
                    playerView.player = target
                }
            },
            onReset = { playerView ->
                playerView.player = null
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("media3_player_view")
        )

        // Detach player on view disposal without releasing caller-owned player
        DisposableEffect(player) {
            onDispose {
                // Caller maintains player ownership
            }
        }

        // 2. Tap surface to toggle controls visibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (enableControls) {
                        areControlsVisible = !areControlsVisible
                    }
                    onSurfaceClick?.invoke()
                }
                .testTag("tap_surface_toggle")
        )

        // 3. Playback Controls & Status Overlays
        if (enableControls) {
            VideoPlayerControlsOverlay(
                isPlaying = isPlaying,
                playbackState = playbackState,
                errorMessage = errorMessage,
                areControlsVisible = areControlsVisible,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                scrubPositionMs = scrubPositionMs,
                onScrubChange = { fraction ->
                    isScrubbing = true
                    val maxDuration = maxOf(1L, totalDurationMs)
                    scrubPositionMs = (fraction * maxDuration).toLong()
                },
                onScrubFinished = {
                    player.seekTo(scrubPositionMs)
                    currentPositionMs = scrubPositionMs
                    isScrubbing = false
                },
                onPlayPauseToggle = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        if (playbackState == PlaybackState.ENDED) {
                            player.seekTo(0L)
                        }
                        player.play()
                    }
                },
                onRetry = {
                    onRetry?.invoke() ?: player.prepare()
                }
            )
        }
    }
}

/**
 * Isolated overlay rendering center controls, buffering progress, error states, and bottom scrubber.
 */
@Composable
fun VideoPlayerControlsOverlay(
    isPlaying: Boolean,
    playbackState: PlaybackState,
    errorMessage: String?,
    areControlsVisible: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    scrubPositionMs: Long,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayPosition = if (isScrubbing) scrubPositionMs else currentPositionMs
    val safeDuration = maxOf(1L, totalDurationMs)
    val sliderValue = (displayPosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("video_controls_overlay")
    ) {
        // Buffering Indicator
        if (playbackState == PlaybackState.BUFFERING && errorMessage == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("buffering_container"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("buffering_indicator")
                        )
                    }
                }
            }
        }

        // Error State Overlay
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Consume taps */ }
                    .padding(24.dp)
                    .testTag("error_container"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Playback Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("error_icon")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.testTag("error_text")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.testTag("retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Retry")
                    }
                }
            }
        }

        // Interactive Controls (Center Play/Pause & Bottom Control Bar)
        AnimatedVisibility(
            visible = areControlsVisible && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Center Play/Pause/Replay Action Button
                if (playbackState != PlaybackState.BUFFERING) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(68.dp)
                            .testTag("center_action_container")
                    ) {
                        IconButton(
                            onClick = onPlayPauseToggle,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("center_play_pause_button")
                        ) {
                            val icon = when {
                                playbackState == PlaybackState.ENDED -> Icons.Rounded.Replay
                                isPlaying -> Icons.Rounded.Pause
                                else -> Icons.Rounded.PlayArrow
                            }
                            val desc = when {
                                playbackState == PlaybackState.ENDED -> "Replay"
                                isPlaying -> "Pause"
                                else -> "Play"
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = desc,
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

                // Bottom Control Bar
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Consume touches to isolate from underlying gestures */ }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("bottom_controls_bar")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Progress Slider
                        Slider(
                            value = sliderValue,
                            onValueChange = onScrubChange,
                            onValueChangeFinished = onScrubFinished,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playback_seek_bar")
                        )

                        // Play/Pause button and Duration row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onPlayPauseToggle,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("bottom_play_pause_button")
                                ) {
                                    val icon = when {
                                        playbackState == PlaybackState.ENDED -> Icons.Rounded.Replay
                                        isPlaying -> Icons.Rounded.Pause
                                        else -> Icons.Rounded.PlayArrow
                                    }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = VideoTimeFormatter.formatMs(displayPosition),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    modifier = Modifier.testTag("current_time_text")
                                )
                                Text(
                                    text = " / ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = VideoTimeFormatter.formatMs(totalDurationMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.testTag("total_duration_text")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
