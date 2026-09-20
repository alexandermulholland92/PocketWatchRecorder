package com.pocket.watchrecorder.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for a stall that showed up on real hardware: after one
 * failed attempt the UI sat on "retrying · 1" forever, because the bandwidth
 * guard passed the entry over on every subsequent run and its attempt count
 * never moved again.
 */
class ShouldAttemptUploadTest {

    private val small = LARGE_FILE_BYTES - 1
    private val large = LARGE_FILE_BYTES + 1

    /** The Wear Bluetooth proxy usually reports no upstream estimate at all. */
    private val noEstimate = 0

    private val slowLink = MIN_UPSTREAM_KBPS_FOR_LARGE - 1
    private val fastLink = MIN_UPSTREAM_KBPS_FOR_LARGE + 1

    @Test
    fun `a small recording is never held back`() {
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = small,
                isWifi = false, upstreamKbps = slowLink
            )
        )
    }

    @Test
    fun `an unknown upstream estimate is not evidence of a slow link`() {
        // The bug: 0 kbps means "the platform has no estimate", and reading it
        // as "too slow" deferred every recording over about a minute, forever.
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = large,
                isWifi = false, upstreamKbps = noEstimate
            )
        )
        // A missing NetworkCapabilities object is passed through as -1.
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = large,
                isWifi = false, upstreamKbps = -1
            )
        )
    }

    @Test
    fun `an entry that has already been attempted is never deferred again`() {
        // The other half of the stall: once an attempt has been spent, the
        // entry has an error recorded, so deferring it leaves the UI showing a
        // retry counter for retries that are not happening.
        assertTrue(
            shouldAttemptUpload(
                attempts = 1, deferrals = 0, bytes = large,
                isWifi = false, upstreamKbps = slowLink
            )
        )
    }

    @Test
    fun `deferrals are capped so an entry cannot be starved`() {
        for (deferrals in 0 until MAX_LINK_DEFERRALS) {
            assertFalse(
                "deferral $deferrals should still hold out for a better link",
                shouldAttemptUpload(
                    attempts = 0, deferrals = deferrals, bytes = large,
                    isWifi = false, upstreamKbps = slowLink
                )
            )
        }
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = MAX_LINK_DEFERRALS, bytes = large,
                isWifi = false, upstreamKbps = slowLink
            )
        )
    }

    @Test
    fun `a genuinely slow link still defers a large recording the first few times`() {
        assertFalse(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = large,
                isWifi = false, upstreamKbps = slowLink
            )
        )
    }

    @Test
    fun `wifi and a fast link both carry a large recording`() {
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = large,
                isWifi = true, upstreamKbps = slowLink
            )
        )
        assertTrue(
            shouldAttemptUpload(
                attempts = 0, deferrals = 0, bytes = large,
                isWifi = false, upstreamKbps = fastLink
            )
        )
    }
}

class DeferredEntryStateTest {

    private fun entry(
        uploaded: Boolean = false,
        attempts: Int = 0,
        lastError: String? = null,
        deferrals: Int = 0
    ) = QueuedUpload(
        id = "id",
        fileName = "id-watch.m4a",
        title = "Recording",
        durationSeconds = 90,
        recordedAt = "2026-09-20T15:28:00",
        uploaded = uploaded,
        attempts = attempts,
        lastError = lastError,
        deferrals = deferrals
    )

    @Test
    fun `being held back for bandwidth is distinct from retrying`() {
        assertTrue(entry(deferrals = 1).awaitingFasterLink)
        assertFalse(entry(deferrals = 0).awaitingFasterLink)
        // Once it lands, or once it has given up, it is no longer waiting.
        assertFalse(entry(deferrals = 1, uploaded = true).awaitingFasterLink)
        assertFalse(
            entry(
                deferrals = 1,
                attempts = UploadQueue.MAX_ATTEMPTS,
                lastError = "HTTP 400"
            ).awaitingFasterLink
        )
    }

    @Test
    fun `a permanent failure is spent at once rather than over five attempts`() {
        // UploadWorker records MAX_ATTEMPTS for a permanent error, so the user
        // sees the reason immediately instead of watching a counter climb for
        // four minutes of backoff before anything is shown.
        val permanentlyFailed = entry(attempts = UploadQueue.MAX_ATTEMPTS, lastError = "HTTP 400")
        assertTrue(permanentlyFailed.isDeadLettered)
        assertFalse(permanentlyFailed.needsUpload)
    }

    @Test
    fun `an entry still has retries left while its error is transient`() {
        val transient = entry(attempts = 1, lastError = "SocketTimeoutException")
        assertFalse(transient.isDeadLettered)
        assertTrue(transient.needsUpload)
    }
}
