package com.gallerysync.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AndroidX Media3 (ExoPlayer) implementation of [GalleryVideoPlayer].
 *
 * Configured exclusively for playing local media content (MediaStore content:// or local file:// URIs).
 * Any non-local or invalid URIs (such as remote http/https URLs or Telegram file IDs) are strictly
 * rejected during instantiation.
 */
class Media3VideoPlayer(
    context: Context,
    override val mediaUri: Uri,
    customPlayer: Player? = null,
    autoPrepare: Boolean = true
) : GalleryVideoPlayer {

    init {
        LocalMediaUriValidator.requireLocalMediaUri(mediaUri)
    }

    private val internalPlayer: Player = customPlayer ?: ExoPlayer.Builder(context.applicationContext).build()

    @Volatile
    override var isReleased: Boolean = false
        private set

    private val _isPlaying = MutableStateFlow(internalPlayer.isPlaying)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    override val playWhenReady: Boolean
        get() = if (isReleased) false else internalPlayer.playWhenReady

    private val _playbackState = MutableStateFlow(mapMedia3State(internalPlayer.playbackState))
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = mapMedia3State(state)
        }

        override fun onPlayerError(playbackException: PlaybackException) {
            _error.value = playbackException.message ?: "Playback error: ${playbackException.errorCodeName}"
        }
    }

    init {
        internalPlayer.addListener(playerListener)
        val mediaItem = MediaItem.fromUri(mediaUri)
        internalPlayer.setMediaItem(mediaItem)
        if (autoPrepare) {
            internalPlayer.prepare()
        }
    }

    override val player: Player?
        get() = if (isReleased) null else internalPlayer

    override val currentPosition: Long
        get() = if (isReleased) 0L else maxOf(0L, internalPlayer.currentPosition)

    override val duration: Long
        get() {
            if (isReleased) return 0L
            val d = internalPlayer.duration
            return if (d < 0L || d == C.TIME_UNSET) 0L else d
        }

    override fun prepare() {
        if (isReleased) return
        internalPlayer.prepare()
    }

    override fun play() {
        if (isReleased) return
        internalPlayer.play()
    }

    override fun pause() {
        if (isReleased) return
        internalPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (isReleased) return
        val target = maxOf(0L, positionMs)
        internalPlayer.seekTo(target)
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        try {
            internalPlayer.removeListener(playerListener)
            internalPlayer.release()
        } catch (_: Exception) {
            // Ignored on cleanup
        }
        _isPlaying.value = false
        _playbackState.value = PlaybackState.IDLE
    }

    companion object {
        fun create(
            context: Context,
            uri: Uri,
            autoPrepare: Boolean = true
        ): Media3VideoPlayer {
            return Media3VideoPlayer(context, uri, autoPrepare = autoPrepare)
        }

        private fun mapMedia3State(state: Int): PlaybackState {
            return when (state) {
                Player.STATE_IDLE -> PlaybackState.IDLE
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_READY -> PlaybackState.READY
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            }
        }
    }
}
