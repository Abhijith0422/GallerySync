package com.gallerysync.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Coordinates playback lifecycle and horizontal pager transitions for a single media viewer page.
 *
 * Core responsibilities:
 * - **Lazy Creation**: Player allocation is deferred until [isCurrentPage] becomes true,
 *   preventing adjacent precomposed pages from allocating ExoPlayer instances or codecs.
 * - **Single Active Player**: Automatically pauses playback as soon as [isCurrentPage] becomes false.
 * - **Android Lifecycle**: Handles [Lifecycle.Event.ON_PAUSE] and [Lifecycle.Event.ON_STOP] by capturing
 *   playback intent before the first lifecycle pause. Subsequent events (such as ON_STOP following ON_PAUSE)
 *   cannot overwrite the captured intent.
 * - **Intent Restoration**: Restores playback on [Lifecycle.Event.ON_RESUME] only if the video had active
 *   playback intent prior to pause, the same video page remains active, and the player is not released.
 * - **Deterministic Cleanup**: Ensures caller-owned player resources are released exactly once upon
 *   [Lifecycle.Event.ON_DESTROY] or permanent Composable disposal.
 * - **Duplicate Prevention**: Retains the existing prepared player instance across temporary pager
 *   transitions without reallocating duplicate instances when returning to the page.
 */
class VideoPlaybackCoordinator(
    private val playerFactory: () -> GalleryVideoPlayer
) : LifecycleEventObserver {

    var player: GalleryVideoPlayer? by mutableStateOf(null)
        private set

    var isCurrentPage: Boolean by mutableStateOf(false)
        private set

    /**
     * True if the player was actively playing or buffering with play intent
     * when the host was backgrounded by the Android lifecycle.
     */
    var wasPlayingBeforePause: Boolean = false
        private set

    /**
     * Guards against subsequent lifecycle pause events (e.g., ON_STOP after ON_PAUSE)
     * overwriting the captured playback intent with the already-paused player state.
     */
    var isPausedByLifecycle: Boolean = false
        private set

    var isReleased: Boolean = false
        private set

    /**
     * Updates the page selection state.
     *
     * @param isCurrent Whether this page is the actively selected/centered page in the horizontal pager.
     */
    fun onPageSelected(isCurrent: Boolean) {
        if (isReleased) return
        isCurrentPage = isCurrent

        if (isCurrent) {
            // Lazy creation: allocate player only when this page is active
            if (player == null) {
                val newPlayer = playerFactory()
                newPlayer.prepare()
                player = newPlayer
            }
        } else {
            // Inactive page: immediately pause playback and clear any pending lifecycle resume intent
            player?.pause()
            wasPlayingBeforePause = false
            isPausedByLifecycle = false
        }
    }

    /**
     * Observes Android host Lifecycle events (Activity / Composable).
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (isReleased) return
        when (event) {
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                handleLifecyclePause()
            }
            Lifecycle.Event.ON_RESUME -> {
                handleLifecycleResume()
            }
            Lifecycle.Event.ON_DESTROY -> {
                release()
            }
            else -> {}
        }
    }

    private fun handleLifecyclePause() {
        if (isReleased) return
        val activePlayer = player
        if (isCurrentPage && activePlayer != null) {
            if (!isPausedByLifecycle) {
                // Capture play intent on the first lifecycle pause transition before pausing
                wasPlayingBeforePause = activePlayer.playWhenReady || activePlayer.isPlaying.value
                isPausedByLifecycle = true
            }
            activePlayer.pause()
        }
    }

    private fun handleLifecycleResume() {
        if (isReleased) return
        val activePlayer = player
        if (isCurrentPage && wasPlayingBeforePause && activePlayer != null && !activePlayer.isReleased) {
            activePlayer.play()
        }
        wasPlayingBeforePause = false
        isPausedByLifecycle = false
    }

    /**
     * Permanently releases owned player resources.
     * Idempotent and safe to invoke multiple times.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        player?.release()
        player = null
        wasPlayingBeforePause = false
        isPausedByLifecycle = false
    }
}
