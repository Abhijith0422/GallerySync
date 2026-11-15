package com.gallerysync.app.player

import java.util.Locale

/**
 * Utility for formatting playback time and durations into readable strings (e.g. 00:00, 02:14, 1:05:30).
 */
object VideoTimeFormatter {

    /**
     * Formats a duration or position in milliseconds into a formatted time string.
     * Returns "00:00" for non-positive or null values.
     */
    fun formatMs(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "00:00"
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
