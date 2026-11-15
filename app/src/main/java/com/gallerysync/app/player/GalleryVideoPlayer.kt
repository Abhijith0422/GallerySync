package com.gallerysync.app.player

import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight abstraction over video playback in GallerySync.
 * Wraps AndroidX Media3 Player functionality while providing observable state
 * and safe lifecycle handling for UI components.
 */
interface GalleryVideoPlayer {

    /**
     * The local media URI being played.
     */
    val mediaUri: Uri

    /**
     * The underlying Media3 [Player] instance, or `null` once [release] has been invoked.
     * Can be passed directly to Media3 UI components (e.g., PlayerView).
     */
    val player: Player?

    /**
     * Observable state indicating whether media is currently actively playing.
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * Whether the player intends to play as soon as media is buffered/ready.
     * True when actively playing or when buffering following a play command.
     */
    val playWhenReady: Boolean
        get() = isPlaying.value

    /**
     * Observable [PlaybackState] (IDLE, BUFFERING, READY, ENDED).
     */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Observable error message if a playback failure occurred, or `null` if healthy.
     */
    val error: StateFlow<String?>

    /**
     * Current playback position in milliseconds. Returns 0 if unset or released.
     */
    val currentPosition: Long

    /**
     * Total duration of the media in milliseconds. Returns 0 if unset, unknown, or released.
     */
    val duration: Long

    /**
     * Returns true if the player has been released.
     */
    val isReleased: Boolean

    /**
     * Prepares media for playback. Safe no-op if already released.
     */
    fun prepare()

    /**
     * Starts or resumes playback. Safe no-op if already released.
     */
    fun play()

    /**
     * Pauses playback. Safe no-op if already released.
     */
    fun pause()

    /**
     * Seeks to a specific playback position in milliseconds. Safe no-op if already released.
     */
    fun seekTo(positionMs: Long)

    /**
     * Releases player resources. After calling release, subsequent calls to
     * [play], [pause], [seekTo], or [prepare] are safe no-ops, and [player] returns `null`.
     */
    fun release()
}
