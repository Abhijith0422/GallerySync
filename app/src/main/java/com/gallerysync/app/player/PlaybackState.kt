package com.gallerysync.app.player

/**
 * High-level playback state for video playback in GallerySync.
 * Maps directly from Media3 [androidx.media3.common.Player] state constants.
 */
enum class PlaybackState {
    /**
     * Player is idle, either not yet prepared or has been reset.
     */
    IDLE,

    /**
     * Player cannot immediately play because not enough data is buffered.
     */
    BUFFERING,

    /**
     * Player is able to immediately play from its current position.
     */
    READY,

    /**
     * Player has finished playing the media.
     */
    ENDED
}
