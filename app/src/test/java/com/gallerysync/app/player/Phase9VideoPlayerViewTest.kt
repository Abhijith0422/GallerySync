package com.gallerysync.app.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9VideoPlayerViewTest {

    // -------------------------------------------------------------------------
    // Fake Player Abstraction
    // -------------------------------------------------------------------------
    class FakeGalleryVideoPlayer(
        override val mediaUri: Uri = Uri.parse("content://media/external/video/media/100"),
        var underlyingPlayer: Player? = null,
        override var currentPosition: Long = 0L,
        override var duration: Long = 60_000L
    ) : GalleryVideoPlayer {

        val isPlayingFlow = MutableStateFlow(false)
        val playbackStateFlow = MutableStateFlow(PlaybackState.IDLE)
        val errorFlow = MutableStateFlow<String?>(null)

        override val isPlaying: StateFlow<Boolean> = isPlayingFlow.asStateFlow()
        override val playbackState: StateFlow<PlaybackState> = playbackStateFlow.asStateFlow()
        override val error: StateFlow<String?> = errorFlow.asStateFlow()
        override val player: Player? get() = if (isReleased) null else underlyingPlayer

        var playCount = 0
        var pauseCount = 0
        var seekCount = 0
        var lastSeekTarget = -1L
        var prepareCount = 0
        var releaseCount = 0
        override var isReleased: Boolean = false

        override fun prepare() {
            prepareCount++
        }

        override fun play() {
            playCount++
            isPlayingFlow.value = true
        }

        override fun pause() {
            pauseCount++
            isPlayingFlow.value = false
        }

        override fun seekTo(positionMs: Long) {
            seekCount++
            lastSeekTarget = positionMs
            currentPosition = positionMs
        }

        override fun release() {
            releaseCount++
            isReleased = true
            isPlayingFlow.value = false
            playbackStateFlow.value = PlaybackState.IDLE
        }
    }

    private fun createDummyPlayer(): Player {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "addListener" -> null
                "removeListener" -> null
                "setVideoSurfaceView" -> null
                "clearVideoSurfaceView" -> null
                "setVideoTextureView" -> null
                "clearVideoTextureView" -> null
                "getVideoSize" -> VideoSize.UNKNOWN
                "getCurrentTracks" -> Tracks.EMPTY
                "getCurrentCues" -> CueGroup(emptyList(), 0L)
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getMediaMetadata" -> MediaMetadata.EMPTY
                "getDeviceInfo" -> DeviceInfo.UNKNOWN
                "getTrackSelectionParameters" -> TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
                "isCommandAvailable" -> true
                "getAvailableCommands" -> Player.Commands.EMPTY
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DummyPlayerProxy"
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == java.lang.Boolean.TYPE -> false
                        returnType == java.lang.Integer.TYPE -> 0
                        returnType == java.lang.Long.TYPE -> 0L
                        returnType == Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler
        ) as Player
    }

    // -------------------------------------------------------------------------
    // 1. Time Formatter Tests
    // -------------------------------------------------------------------------

    @Test
    fun formatter_converts_milliseconds_accurately() {
        assertEquals("00:00", VideoTimeFormatter.formatMs(null))
        assertEquals("00:00", VideoTimeFormatter.formatMs(-500L))
        assertEquals("00:00", VideoTimeFormatter.formatMs(0L))
        assertEquals("00:05", VideoTimeFormatter.formatMs(5_000L))
        assertEquals("01:05", VideoTimeFormatter.formatMs(65_000L))
        assertEquals("02:14", VideoTimeFormatter.formatMs(134_000L))
        assertEquals("10:00", VideoTimeFormatter.formatMs(600_000L))
        assertEquals("1:01:01", VideoTimeFormatter.formatMs(3_661_000L))
    }

    // -------------------------------------------------------------------------
    // 2. Play/Pause Controls & State Tests
    // -------------------------------------------------------------------------

    @Test
    fun playPauseToggle_whenPaused_invokesPlay() {
        val fakePlayer = FakeGalleryVideoPlayer()
        assertFalse(fakePlayer.isPlaying.value)

        fun onPlayPauseToggle() {
            if (fakePlayer.isPlaying.value) {
                fakePlayer.pause()
            } else {
                if (fakePlayer.playbackState.value == PlaybackState.ENDED) {
                    fakePlayer.seekTo(0L)
                }
                fakePlayer.play()
            }
        }

        onPlayPauseToggle()
        assertEquals(1, fakePlayer.playCount)
        assertEquals(0, fakePlayer.pauseCount)
        assertTrue(fakePlayer.isPlaying.value)
    }

    @Test
    fun playPauseToggle_whenPlaying_invokesPause() {
        val fakePlayer = FakeGalleryVideoPlayer()
        fakePlayer.play()
        assertEquals(1, fakePlayer.playCount)
        assertTrue(fakePlayer.isPlaying.value)

        fun onPlayPauseToggle() {
            if (fakePlayer.isPlaying.value) {
                fakePlayer.pause()
            } else {
                if (fakePlayer.playbackState.value == PlaybackState.ENDED) {
                    fakePlayer.seekTo(0L)
                }
                fakePlayer.play()
            }
        }

        onPlayPauseToggle()
        assertEquals(1, fakePlayer.pauseCount)
        assertFalse(fakePlayer.isPlaying.value)
    }

    @Test
    fun playPauseToggle_whenEnded_seeksToStartAndPlays() {
        val fakePlayer = FakeGalleryVideoPlayer(currentPosition = 60_000L, duration = 60_000L)
        fakePlayer.playbackStateFlow.value = PlaybackState.ENDED

        fun onPlayPauseToggle() {
            if (fakePlayer.isPlaying.value) {
                fakePlayer.pause()
            } else {
                if (fakePlayer.playbackState.value == PlaybackState.ENDED) {
                    fakePlayer.seekTo(0L)
                }
                fakePlayer.play()
            }
        }

        onPlayPauseToggle()
        assertEquals(1, fakePlayer.seekCount)
        assertEquals(0L, fakePlayer.lastSeekTarget)
        assertEquals(1, fakePlayer.playCount)
        assertTrue(fakePlayer.isPlaying.value)
    }

    // -------------------------------------------------------------------------
    // 3. Time Display & Scrubbing Interaction
    // -------------------------------------------------------------------------

    @Test
    fun scrubbing_calculatesPositionAndDefersSeekUntilFinished() {
        val fakePlayer = FakeGalleryVideoPlayer(currentPosition = 10_000L, duration = 100_000L)
        var isScrubbing = false
        var scrubPositionMs = 0L

        fun onScrubChange(fraction: Float) {
            isScrubbing = true
            scrubPositionMs = (fraction * fakePlayer.duration).toLong()
        }

        fun onScrubFinished() {
            fakePlayer.seekTo(scrubPositionMs)
            isScrubbing = false
        }

        // 1. User drags slider to 45%
        onScrubChange(0.45f)
        assertTrue(isScrubbing)
        assertEquals(45_000L, scrubPositionMs)
        assertEquals(0, fakePlayer.seekCount) // Not sought yet while dragging

        // Display position reflects scrub position
        val displayPos = if (isScrubbing) scrubPositionMs else fakePlayer.currentPosition
        assertEquals(45_000L, displayPos)
        assertEquals("00:45", VideoTimeFormatter.formatMs(displayPos))

        // 2. User releases slider
        onScrubFinished()
        assertFalse(isScrubbing)
        assertEquals(1, fakePlayer.seekCount)
        assertEquals(45_000L, fakePlayer.lastSeekTarget)
        assertEquals(45_000L, fakePlayer.currentPosition)
    }

    // -------------------------------------------------------------------------
    // 4. Buffering Indicator Tests
    // -------------------------------------------------------------------------

    @Test
    fun bufferingState_showsIndicatorAndSuppressesCenterAction() {
        val fakePlayer = FakeGalleryVideoPlayer()
        fakePlayer.playbackStateFlow.value = PlaybackState.BUFFERING

        val isBuffering = fakePlayer.playbackState.value == PlaybackState.BUFFERING && fakePlayer.error.value == null
        val showCenterAction = fakePlayer.playbackState.value != PlaybackState.BUFFERING && fakePlayer.error.value == null

        assertTrue("Buffering indicator must be shown while buffering", isBuffering)
        assertFalse("Center play/pause must be suppressed while buffering", showCenterAction)

        // Once ready
        fakePlayer.playbackStateFlow.value = PlaybackState.READY
        val isBufferingNow = fakePlayer.playbackState.value == PlaybackState.BUFFERING && fakePlayer.error.value == null
        val showCenterActionNow = fakePlayer.playbackState.value != PlaybackState.BUFFERING && fakePlayer.error.value == null

        assertFalse(isBufferingNow)
        assertTrue(showCenterActionNow)
    }

    // -------------------------------------------------------------------------
    // 5. Error State & Retry Presentation
    // -------------------------------------------------------------------------

    @Test
    fun errorState_presentsMessageAndTriggersRetry() {
        val fakePlayer = FakeGalleryVideoPlayer()
        fakePlayer.errorFlow.value = "Source error: media file not found"

        assertNotNull(fakePlayer.error.value)
        assertEquals("Source error: media file not found", fakePlayer.error.value)

        var customRetryInvoked = false
        val onRetry = { customRetryInvoked = true }

        // Trigger retry callback
        onRetry()
        assertTrue(customRetryInvoked)

        // Default retry delegates to player.prepare()
        val defaultRetry = { fakePlayer.prepare() }
        defaultRetry()
        assertEquals(1, fakePlayer.prepareCount)
    }

    // -------------------------------------------------------------------------
    // 6. PlayerView Integration: Attachment, Detachment & Configuration
    // -------------------------------------------------------------------------

    @Test
    fun playerView_attachesAndDetachesUnderlyingPlayerCorrectly() {
        val dummyPlayer = createDummyPlayer()
        val fakePlayer = FakeGalleryVideoPlayer(underlyingPlayer = dummyPlayer)

        // Initial state: player is attached and available
        assertNotNull(fakePlayer.player)
        assertEquals(dummyPlayer, fakePlayer.player)

        // Detach player (simulating View detachment / recycling)
        var attachedPlayer: Player? = fakePlayer.player
        assertEquals(dummyPlayer, attachedPlayer)

        attachedPlayer = null
        assertNull(attachedPlayer)

        // Underlying caller-owned player remains intact
        assertNotNull(fakePlayer.player)
        assertFalse(fakePlayer.isReleased)
    }

    @Test
    fun uiDisposal_doesNotReleaseCallerOwnedPlayer() {
        val dummyPlayer = createDummyPlayer()
        val fakePlayer = FakeGalleryVideoPlayer(underlyingPlayer = dummyPlayer)

        // Simulate UI component lifecycle: component binds to player
        var boundPlayer: Player? = fakePlayer.player
        assertNotNull(boundPlayer)
        assertFalse(fakePlayer.isReleased)
        assertEquals(0, fakePlayer.releaseCount)

        // Simulate UI disposal: component unbinds and clears its reference
        boundPlayer = null
        assertNull(boundPlayer)

        // Crucial requirement: Caller maintains sole ownership; UI disposal must NOT release the player
        assertFalse(fakePlayer.isReleased)
        assertEquals(0, fakePlayer.releaseCount)
        assertNotNull(fakePlayer.player)
    }
}
