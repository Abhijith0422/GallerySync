package com.gallerysync.app.player

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 9.4.3: Unit tests verifying video player lifecycle, pager coordination,
 * single-active-player semantics, Android lifecycle integration, playback intent preservation,
 * and caller ownership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9PagerLifecycleIntegrationTest {

    // -------------------------------------------------------------------------
    // Test Doubles
    // -------------------------------------------------------------------------

    private class FakeGalleryVideoPlayer(
        override val mediaUri: Uri = Uri.parse("content://media/external/video/media/1"),
        override var currentPosition: Long = 0L,
        override var duration: Long = 10_000L
    ) : GalleryVideoPlayer {

        val isPlayingFlow = MutableStateFlow(false)
        val playbackStateFlow = MutableStateFlow(PlaybackState.IDLE)
        val errorFlow = MutableStateFlow<String?>(null)

        var playWhenReadyState: Boolean = false
        override val playWhenReady: Boolean
            get() = playWhenReadyState || isPlayingFlow.value

        override val isPlaying: StateFlow<Boolean> = isPlayingFlow.asStateFlow()
        override val playbackState: StateFlow<PlaybackState> = playbackStateFlow.asStateFlow()
        override val error: StateFlow<String?> = errorFlow.asStateFlow()
        override val player: Player? = null

        var playCount = 0
        var pauseCount = 0
        var prepareCount = 0
        var releaseCount = 0
        override var isReleased: Boolean = false

        override fun prepare() {
            prepareCount++
            playbackStateFlow.value = PlaybackState.READY
        }

        override fun play() {
            playCount++
            playWhenReadyState = true
            isPlayingFlow.value = true
        }

        override fun pause() {
            pauseCount++
            playWhenReadyState = false
            isPlayingFlow.value = false
        }

        override fun seekTo(positionMs: Long) {
            currentPosition = positionMs
        }

        override fun release() {
            releaseCount++
            isReleased = true
            playWhenReadyState = false
            isPlayingFlow.value = false
            playbackStateFlow.value = PlaybackState.IDLE
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    // -------------------------------------------------------------------------
    // A. Pager Coordination Tests
    // -------------------------------------------------------------------------

    @Test
    fun `adjacent precomposed page does not create active player instance`() {
        var factoryCallCount = 0
        val coordinator = VideoPlaybackCoordinator {
            factoryCallCount++
            FakeGalleryVideoPlayer()
        }

        // Pager pre-composes adjacent page where isCurrentPage == false
        coordinator.onPageSelected(isCurrent = false)

        assertNull("Adjacent page must not instantiate player", coordinator.player)
        assertEquals("Factory must not be invoked for inactive page", 0, factoryCallCount)
        assertFalse("Page must be marked inactive", coordinator.isCurrentPage)
    }

    @Test
    fun `active page lazily creates and prepares player`() {
        var factoryCallCount = 0
        val coordinator = VideoPlaybackCoordinator {
            factoryCallCount++
            FakeGalleryVideoPlayer()
        }

        // Page becomes selected/active
        coordinator.onPageSelected(isCurrent = true)

        assertNotNull("Active page must allocate player", coordinator.player)
        assertEquals("Factory must be called exactly once", 1, factoryCallCount)
        assertTrue("Page must be marked active", coordinator.isCurrentPage)
        assertEquals("Player must be prepared on activation", 1, (coordinator.player as FakeGalleryVideoPlayer).prepareCount)
    }

    @Test
    fun `inactive page immediately pauses player`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }

        // Start active and playing
        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()
        assertTrue("Player should be playing", fakePlayer.isPlaying.value)

        // User swipes away -> page becomes inactive
        coordinator.onPageSelected(isCurrent = false)

        assertFalse("Player must be paused immediately when page is inactive", fakePlayer.isPlaying.value)
        assertEquals(1, fakePlayer.pauseCount)
        assertFalse("wasPlayingBeforePause should be reset on page swipe away", coordinator.wasPlayingBeforePause)
    }

    @Test
    fun `switching from video to image pauses video player`() {
        val videoPlayer = FakeGalleryVideoPlayer()
        val videoCoordinator = VideoPlaybackCoordinator { videoPlayer }

        // Page 0 (video) is active and playing
        videoCoordinator.onPageSelected(isCurrent = true)
        videoPlayer.play()
        assertTrue(videoPlayer.isPlaying.value)

        // Pager switches to Page 1 (image) -> Page 0 gets onPageSelected(false)
        videoCoordinator.onPageSelected(isCurrent = false)

        assertFalse("Video player must pause when switching to image page", videoPlayer.isPlaying.value)
        assertEquals(1, videoPlayer.pauseCount)
    }

    @Test
    fun `switching between two videos leaves only active video eligible to play`() {
        val videoPlayer1 = FakeGalleryVideoPlayer()
        val coordinator1 = VideoPlaybackCoordinator { videoPlayer1 }

        val videoPlayer2 = FakeGalleryVideoPlayer()
        val coordinator2 = VideoPlaybackCoordinator { videoPlayer2 }

        // Initial: Video 1 is active & playing; Video 2 is precomposed & inactive
        coordinator1.onPageSelected(isCurrent = true)
        videoPlayer1.play()
        coordinator2.onPageSelected(isCurrent = false)

        assertTrue("Video 1 is playing", videoPlayer1.isPlaying.value)
        assertNull("Video 2 player is not yet instantiated", coordinator2.player)

        // User swipes from Video 1 to Video 2
        coordinator1.onPageSelected(isCurrent = false)
        coordinator2.onPageSelected(isCurrent = true)

        assertFalse("Video 1 must pause immediately", videoPlayer1.isPlaying.value)
        assertNotNull("Video 2 player must now be instantiated", coordinator2.player)
        assertEquals("Video 2 must be prepared", 1, videoPlayer2.prepareCount)

        // Start playback on Video 2
        videoPlayer2.play()
        assertTrue("Video 2 is playing", videoPlayer2.isPlaying.value)
        assertFalse("Video 1 remains paused", videoPlayer1.isPlaying.value)
    }

    @Test
    fun `returning to previously viewed video does not duplicate player`() {
        var factoryCallCount = 0
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator {
            factoryCallCount++
            fakePlayer
        }

        // 1. Visit video page
        coordinator.onPageSelected(isCurrent = true)
        assertEquals(1, factoryCallCount)
        val firstPlayer = coordinator.player

        // 2. Swipe away
        coordinator.onPageSelected(isCurrent = false)

        // 3. Return to video page
        coordinator.onPageSelected(isCurrent = true)

        assertEquals("Factory must not be called again", 1, factoryCallCount)
        assertSame("Must retain identical player instance", firstPlayer, coordinator.player)
        assertFalse("Retained player must not be released prematurely", fakePlayer.isReleased)
    }

    // -------------------------------------------------------------------------
    // B. Android Lifecycle & Playback Intent Tests
    // -------------------------------------------------------------------------

    @Test
    fun `ON_PAUSE pauses playing player and records intent`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()
        assertTrue(fakePlayer.isPlaying.value)

        // Simulate ON_PAUSE
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)

        assertFalse("Playback must be paused on ON_PAUSE", fakePlayer.isPlaying.value)
        assertTrue("Coordinator must record that video had play intent prior to pause", coordinator.wasPlayingBeforePause)
        assertTrue("isPausedByLifecycle must be true", coordinator.isPausedByLifecycle)
    }

    @Test
    fun `ON_PAUSE followed by ON_STOP preserves the original playback intent`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()
        assertTrue(fakePlayer.isPlaying.value)

        // 1. ON_PAUSE arrives: pauses player, records intent
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        assertFalse(fakePlayer.isPlaying.value)
        assertTrue(coordinator.wasPlayingBeforePause)

        // 2. ON_STOP arrives: must NOT overwrite wasPlayingBeforePause with paused state
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)

        assertFalse("Playback remains paused", fakePlayer.isPlaying.value)
        assertTrue("Original play intent must be preserved after ON_STOP", coordinator.wasPlayingBeforePause)
    }

    @Test
    fun `ON_PAUSE followed by ON_STOP followed by ON_START and ON_RESUME restores playback`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()

        // Backgrounded: ON_PAUSE -> ON_STOP
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)
        assertFalse(fakePlayer.isPlaying.value)
        assertTrue(coordinator.wasPlayingBeforePause)

        // Returning to foreground: ON_START -> ON_RESUME
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_START)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertTrue("Playback must resume on ON_RESUME", fakePlayer.isPlaying.value)
        assertFalse("wasPlayingBeforePause flag must be reset", coordinator.wasPlayingBeforePause)
        assertFalse("isPausedByLifecycle must be reset", coordinator.isPausedByLifecycle)
    }

    @Test
    fun `manually paused playback does not resume`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        // User manually paused before backgrounding
        fakePlayer.pause()
        assertFalse(fakePlayer.isPlaying.value)
        assertFalse(fakePlayer.playWhenReady)

        // Backgrounded: ON_PAUSE -> ON_STOP
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)
        assertFalse("Intent must remain false for manually paused media", coordinator.wasPlayingBeforePause)

        // Foregrounded: ON_START -> ON_RESUME
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_START)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertFalse("Manually paused playback must not resume on ON_RESUME", fakePlayer.isPlaying.value)
    }

    @Test
    fun `buffering with play intent is handled consistently`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        // Simulate buffering with user play intent: playWhenReady=true, isPlaying=false, state=BUFFERING
        fakePlayer.playWhenReadyState = true
        fakePlayer.isPlayingFlow.value = false
        fakePlayer.playbackStateFlow.value = PlaybackState.BUFFERING

        // Backgrounded
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)

        assertTrue("Buffering with play intent must be recorded as play intent", coordinator.wasPlayingBeforePause)

        // Foregrounded
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertTrue("Playback intent must be restored on ON_RESUME", fakePlayer.isPlaying.value)
    }

    @Test
    fun `repeated ON_STOP events do not overwrite saved intent`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()

        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        assertTrue(coordinator.wasPlayingBeforePause)

        // First ON_STOP
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)
        assertTrue(coordinator.wasPlayingBeforePause)

        // Repeated ON_STOP
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)
        assertTrue("Repeated ON_STOP must not overwrite saved intent", coordinator.wasPlayingBeforePause)

        // Resume restores
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
        assertTrue(fakePlayer.isPlaying.value)
    }

    @Test
    fun `a page that becomes inactive while backgrounded does not resume`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()

        // Backgrounded
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_STOP)

        // Pager moves to another page while app is backgrounded
        coordinator.onPageSelected(isCurrent = false)
        assertFalse("Intent must be cleared when page becomes inactive", coordinator.wasPlayingBeforePause)

        // Foregrounded
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
        assertFalse("Inactive page must not resume playback", fakePlayer.isPlaying.value)
    }

    @Test
    fun `releasing the player prevents lifecycle restoration`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        fakePlayer.play()

        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
        assertTrue(coordinator.wasPlayingBeforePause)

        // Player is released (e.g. permanent navigation pop or viewer close)
        coordinator.release()
        assertTrue(coordinator.isReleased)
        assertNull(coordinator.player)
        assertFalse(coordinator.wasPlayingBeforePause)

        // Subsequent ON_RESUME
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
        assertFalse("Released coordinator must not attempt playback", fakePlayer.isPlaying.value)
    }

    @Test
    fun `ON_DESTROY releases owned player`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }
        val lifecycleOwner = TestLifecycleOwner()

        coordinator.onPageSelected(isCurrent = true)
        assertNotNull(coordinator.player)

        // Host destroyed
        coordinator.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_DESTROY)

        assertTrue("Player must be released on ON_DESTROY", fakePlayer.isReleased)
        assertNull("Coordinator player reference must be cleared", coordinator.player)
        assertTrue("Coordinator must be marked released", coordinator.isReleased)
    }

    // -------------------------------------------------------------------------
    // C. Resource Ownership & Disposal Tests
    // -------------------------------------------------------------------------

    @Test
    fun `each player is released exactly once`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }

        coordinator.onPageSelected(isCurrent = true)
        coordinator.release()

        assertEquals(1, fakePlayer.releaseCount)
        assertTrue(fakePlayer.isReleased)

        // Second release invocation must be idempotent
        coordinator.release()
        assertEquals("Release count must remain 1", 1, fakePlayer.releaseCount)
    }

    @Test
    fun `temporary page inactivity does not release retained player`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }

        coordinator.onPageSelected(isCurrent = true)
        // Temporarily swipe to adjacent page
        coordinator.onPageSelected(isCurrent = false)

        assertFalse("Temporary inactivity must not release the player", fakePlayer.isReleased)
        assertNotNull("Player reference must be retained for swiping back", coordinator.player)
    }

    @Test
    fun `permanent disposal releases player`() {
        val fakePlayer = FakeGalleryVideoPlayer()
        val coordinator = VideoPlaybackCoordinator { fakePlayer }

        coordinator.onPageSelected(isCurrent = true)
        assertNotNull(coordinator.player)

        // Composable disposed
        coordinator.release()

        assertTrue("Player must be released on disposal", fakePlayer.isReleased)
        assertNull("Player reference must be cleared", coordinator.player)
        assertTrue("Coordinator must be released", coordinator.isReleased)
    }
}
