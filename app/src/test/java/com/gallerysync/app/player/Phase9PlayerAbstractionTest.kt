package com.gallerysync.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase9PlayerAbstractionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- Helper to create a fake Media3 Player using standard Java Proxy ---
    private class FakePlayerHandler : InvocationHandler {
        val listeners = mutableListOf<Player.Listener>()
        var isPlaying: Boolean = false
        var playbackState: Int = Player.STATE_IDLE
        var currentPosition: Long = 0L
        var duration: Long = 30_000L
        var isReleased: Boolean = false
        var playCalled: Boolean = false
        var pauseCalled: Boolean = false
        var seekPosition: Long = -1L
        var prepareCalled: Boolean = false
        var setMediaItemCalled: Boolean = false

        fun triggerPlaybackState(state: Int) {
            playbackState = state
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }

        fun triggerIsPlaying(playing: Boolean) {
            isPlaying = playing
            listeners.forEach { it.onIsPlayingChanged(playing) }
        }

        fun triggerError(error: PlaybackException) {
            listeners.forEach { it.onPlayerError(error) }
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
                "addListener" -> {
                    val l = args?.get(0) as? Player.Listener
                    if (l != null) listeners.add(l)
                    return null
                }
                "removeListener" -> {
                    val l = args?.get(0) as? Player.Listener
                    if (l != null) listeners.remove(l)
                    return null
                }
                "play" -> {
                    playCalled = true
                    triggerIsPlaying(true)
                    return null
                }
                "pause" -> {
                    pauseCalled = true
                    triggerIsPlaying(false)
                    return null
                }
                "prepare" -> {
                    prepareCalled = true
                    return null
                }
                "setMediaItem" -> {
                    setMediaItemCalled = true
                    return null
                }
                "seekTo" -> {
                    val pos = args?.get(0) as? Long ?: 0L
                    seekPosition = pos
                    currentPosition = pos
                    return null
                }
                "release" -> {
                    isReleased = true
                    return null
                }
                "isPlaying" -> return isPlaying
                "getPlaybackState" -> return playbackState
                "getCurrentPosition" -> return currentPosition
                "getDuration" -> return duration
                "equals" -> return proxy === args?.get(0)
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "FakeMedia3PlayerProxy"
                else -> {
                    val returnType = method.returnType
                    return when {
                        returnType == java.lang.Boolean.TYPE -> false
                        returnType == java.lang.Integer.TYPE -> 0
                        returnType == java.lang.Long.TYPE -> 0L
                        returnType == java.lang.Float.TYPE -> 0f
                        returnType == java.lang.Double.TYPE -> 0.0
                        returnType == Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
    }

    private fun createFakePlayer(): Pair<Player, FakePlayerHandler> {
        val handler = FakePlayerHandler()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler
        ) as Player
        return Pair(player, handler)
    }

    // -------------------------------------------------------------------------
    // 1. URI Validation Tests
    // -------------------------------------------------------------------------

    @Test
    fun validator_accepts_valid_content_uri() {
        val uri = Uri.parse("content://media/external/video/media/12345")
        assertTrue(LocalMediaUriValidator.isLocalMediaUri(uri))
        val validated = LocalMediaUriValidator.requireLocalMediaUri(uri)
        assertEquals(uri, validated)
    }

    @Test
    fun validator_accepts_valid_file_uri() {
        val uri = Uri.parse("file:///storage/emulated/0/DCIM/Camera/VID_2026.mp4")
        assertTrue(LocalMediaUriValidator.isLocalMediaUri(uri))
        val validated = LocalMediaUriValidator.requireLocalMediaUri(uri)
        assertEquals(uri, validated)
    }

    @Test
    fun validator_rejects_null_and_empty_uris() {
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(null))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(Uri.EMPTY))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(Uri.parse("")))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(Uri.parse("   ")))

        try {
            LocalMediaUriValidator.requireLocalMediaUri(Uri.EMPTY)
            fail("Should have thrown IllegalArgumentException for empty URI")
        } catch (_: IllegalArgumentException) {
            // Success
        }
    }

    @Test
    fun validator_rejects_telegram_file_id() {
        // Raw Telegram file IDs lack a content:// or file:// scheme
        val telegramIdUri = Uri.parse("BAACAgIAAxkBAAIBYWR9e3q9...")
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(telegramIdUri))

        try {
            LocalMediaUriValidator.requireLocalMediaUri(telegramIdUri)
            fail("Should have thrown IllegalArgumentException for Telegram file ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("GallerySync video playback requires local content:// or file:// URIs") == true)
        }
    }

    @Test
    fun validator_rejects_remote_http_and_https_uris() {
        val httpUri = Uri.parse("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        val httpsUri = Uri.parse("https://example.com/video.mp4")

        assertFalse(LocalMediaUriValidator.isLocalMediaUri(httpUri))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(httpsUri))

        try {
            LocalMediaUriValidator.requireLocalMediaUri(httpUri)
            fail("Should have thrown IllegalArgumentException for http URI")
        } catch (_: IllegalArgumentException) {}

        try {
            LocalMediaUriValidator.requireLocalMediaUri(httpsUri)
            fail("Should have thrown IllegalArgumentException for https URI")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun validator_rejects_unsupported_schemes() {
        val tgUri = Uri.parse("tg://resolve?domain=test")
        val ftpUri = Uri.parse("ftp://server/video.mp4")

        assertFalse(LocalMediaUriValidator.isLocalMediaUri(tgUri))
        assertFalse(LocalMediaUriValidator.isLocalMediaUri(ftpUri))
    }

    // -------------------------------------------------------------------------
    // 2. Player Abstraction Construction & URI Safety
    // -------------------------------------------------------------------------

    @Test
    fun player_creation_rejects_non_local_uri_with_exception() {
        val remoteUri = Uri.parse("https://example.com/video.mp4")
        val (fakePlayer, _) = createFakePlayer()

        try {
            Media3VideoPlayer(context, remoteUri, customPlayer = fakePlayer)
            fail("Constructor should fail when instantiated with non-local URI")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("GallerySync video playback requires local content:// or file:// URIs") == true)
        }
    }

    @Test
    fun player_creation_sets_media_item_and_prepares_valid_content_uri() {
        val uri = Uri.parse("content://media/external/video/media/999")
        val (fakePlayer, handler) = createFakePlayer()

        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer, autoPrepare = true)

        assertEquals(uri, player.mediaUri)
        assertTrue(handler.setMediaItemCalled)
        assertTrue(handler.prepareCalled)
        assertFalse(player.isReleased)
        assertNotNull(player.player)
    }

    @Test
    fun player_auto_prepare_flag_respected() {
        val uri = Uri.parse("content://media/external/video/media/1000")
        val (fakePlayer, handler) = createFakePlayer()

        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer, autoPrepare = false)

        assertTrue(handler.setMediaItemCalled)
        assertFalse(handler.prepareCalled)

        player.prepare()
        assertTrue(handler.prepareCalled)
    }

    // -------------------------------------------------------------------------
    // 3. Play, Pause, and Seeking Controls
    // -------------------------------------------------------------------------

    @Test
    fun player_play_and_pause_updates_state_and_delegates() {
        val uri = Uri.parse("content://media/external/video/media/1001")
        val (fakePlayer, handler) = createFakePlayer()
        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        assertFalse(player.isPlaying.value)

        player.play()
        assertTrue(handler.playCalled)
        assertTrue(player.isPlaying.value)

        player.pause()
        assertTrue(handler.pauseCalled)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun player_seekTo_delegates_and_clamps_negative_values() {
        val uri = Uri.parse("content://media/external/video/media/1002")
        val (fakePlayer, handler) = createFakePlayer()
        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        player.seekTo(5000L)
        assertEquals(5000L, handler.seekPosition)
        assertEquals(5000L, player.currentPosition)

        // Clamping negative target to 0L
        player.seekTo(-500L)
        assertEquals(0L, handler.seekPosition)
        assertEquals(0L, player.currentPosition)
    }

    @Test
    fun player_reports_position_and_safe_duration() {
        val uri = Uri.parse("content://media/external/video/media/1003")
        val (fakePlayer, handler) = createFakePlayer()
        handler.currentPosition = 12_500L
        handler.duration = 45_000L

        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        assertEquals(12_500L, player.currentPosition)
        assertEquals(45_000L, player.duration)

        // If duration is unset/negative (e.g. C.TIME_UNSET), safe duration is 0L
        handler.duration = C.TIME_UNSET
        assertEquals(0L, player.duration)
    }

    // -------------------------------------------------------------------------
    // 4. Playback State & Error Transitions
    // -------------------------------------------------------------------------

    @Test
    fun player_state_transitions_map_correctly() {
        val uri = Uri.parse("content://media/external/video/media/1004")
        val (fakePlayer, handler) = createFakePlayer()
        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        assertEquals(PlaybackState.IDLE, player.playbackState.value)

        handler.triggerPlaybackState(Player.STATE_BUFFERING)
        assertEquals(PlaybackState.BUFFERING, player.playbackState.value)

        handler.triggerPlaybackState(Player.STATE_READY)
        assertEquals(PlaybackState.READY, player.playbackState.value)

        handler.triggerPlaybackState(Player.STATE_ENDED)
        assertEquals(PlaybackState.ENDED, player.playbackState.value)

        handler.triggerPlaybackState(Player.STATE_IDLE)
        assertEquals(PlaybackState.IDLE, player.playbackState.value)
    }

    @Test
    fun player_error_listener_updates_error_state() {
        val uri = Uri.parse("content://media/external/video/media/1005")
        val (fakePlayer, handler) = createFakePlayer()
        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        assertNull(player.error.value)

        val exception = PlaybackException("Source error loading stream", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        handler.triggerError(exception)

        assertEquals("Source error loading stream", player.error.value)
    }

    // -------------------------------------------------------------------------
    // 5. Release Behavior and Post-Release Safety
    // -------------------------------------------------------------------------

    @Test
    fun player_release_cleans_up_and_makes_subsequent_operations_noop() {
        val uri = Uri.parse("content://media/external/video/media/1006")
        val (fakePlayer, handler) = createFakePlayer()
        val player = Media3VideoPlayer(context, uri, customPlayer = fakePlayer)

        player.play()
        assertTrue(player.isPlaying.value)
        assertNotNull(player.player)
        assertFalse(player.isReleased)

        player.release()

        assertTrue(player.isReleased)
        assertTrue(handler.isReleased)
        assertNull(player.player)
        assertFalse(player.isPlaying.value)
        assertEquals(PlaybackState.IDLE, player.playbackState.value)
        assertEquals(0L, player.currentPosition)
        assertEquals(0L, player.duration)

        // Verify subsequent calls do not throw and are no-ops
        handler.playCalled = false
        handler.pauseCalled = false
        handler.seekPosition = -1L

        player.play()
        assertFalse(handler.playCalled)

        player.pause()
        assertFalse(handler.pauseCalled)

        player.seekTo(1000L)
        assertEquals(-1L, handler.seekPosition)

        player.prepare()
        player.release() // Duplicate release is safe no-op
    }

    // -------------------------------------------------------------------------
    // 6. Real Media3 ExoPlayer Creation in Robolectric Context
    // -------------------------------------------------------------------------

    @Test
    fun real_exoplayer_creation_and_release_in_robolectric() {
        val uri = Uri.parse("content://media/external/video/media/2000")
        // Constructs real Media3 ExoPlayer without customPlayer double
        val player = Media3VideoPlayer.create(context, uri, autoPrepare = false)

        assertNotNull(player.player)
        assertEquals(uri, player.mediaUri)
        assertFalse(player.isReleased)

        player.release()
        assertTrue(player.isReleased)
        assertNull(player.player)
    }
}
