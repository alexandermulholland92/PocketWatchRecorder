package com.pocket.watchrecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `relative age reads naturally at each scale`() {
        assertEquals("just now", relativeAge(now - 5_000, now))
        assertEquals("4m ago", relativeAge(now - 4 * 60_000, now))
        assertEquals("3h ago", relativeAge(now - 3 * 3_600_000, now))
        assertEquals("2d ago", relativeAge(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `an unset timestamp has no age label`() {
        assertEquals("", relativeAge(0L, now))
    }

    @Test
    fun `a clock that has gone backwards does not render a negative age`() {
        assertEquals("just now", relativeAge(now + 60_000, now))
    }

    @Test
    fun `durations are minutes and padded seconds`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_400))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("100:00", formatDuration(100 * 60_000L))
    }
}
