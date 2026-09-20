package com.pocket.watchrecorder.ui

import java.util.Locale

/**
 * Display formatting.
 *
 * File-scope and `internal` rather than private helpers inside a screen, so
 * they can be unit tested without an emulator.
 */

/** "just now", "4m ago", "3h ago", "2d ago". Empty for an unset timestamp. */
internal fun relativeAge(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val seconds = ((now - epochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

/** Elapsed recording time as m:ss. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
