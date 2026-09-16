package com.pocket.watchrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecordedAtStampTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    /** 2026-09-11 15:30:00 local, i.e. during PDT (UTC-07:00). */
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 11, 15, 30, 0)

    @Test
    fun `the stamp is the start of the recording, not its end`() {
        assertEquals(
            "2026-09-11T15:28:00",
            recordedAtStamp(
                durationMs = 120_000,
                format = RecordedAtFormat.LOCAL_NAIVE,
                zone = zone,
                now = now
            )
        )
    }

    @Test
    fun `seconds are always written, even when they are zero`() {
        // LocalDateTime.toString() would render this as "2026-09-11T15:30",
        // which a strict server-side parser can reject.
        val stamp = recordedAtStamp(0, RecordedAtFormat.LOCAL_NAIVE, zone, now)
        assertTrue("expected seconds in $stamp", stamp.endsWith(":00"))
        assertEquals(19, stamp.length)
    }

    @Test
    fun `each format renders the marker it promises`() {
        assertEquals(
            "2026-09-11T15:30:00",
            recordedAtStamp(0, RecordedAtFormat.LOCAL_NAIVE, zone, now)
        )
        assertEquals(
            "2026-09-11T15:30:00Z",
            recordedAtStamp(0, RecordedAtFormat.LOCAL_AS_UTC, zone, now)
        )
        assertEquals(
            "2026-09-11T15:30:00-07:00",
            recordedAtStamp(0, RecordedAtFormat.OFFSET, zone, now)
        )
    }

    @Test
    fun `sub-second recordings do not roll back a whole second`() {
        assertEquals(
            "2026-09-11T15:29:59",
            recordedAtStamp(500, RecordedAtFormat.LOCAL_NAIVE, zone, now)
        )
    }
}

class QueuedUploadTest {

    private fun entry(
        uploaded: Boolean = false,
        attempts: Int = 0,
        lastError: String? = null,
        summary: String? = null,
        uploadUrl: String? = null,
        recordingId: String? = null,
        urlExpiresAtEpochMs: Long = 0L
    ) = QueuedUpload(
        id = "id",
        fileName = "id-watch.m4a",
        title = "Recording",
        durationSeconds = 30,
        recordedAt = "2026-09-11T15:28:00",
        uploaded = uploaded,
        attempts = attempts,
        lastError = lastError,
        summary = summary,
        uploadUrl = uploadUrl,
        recordingId = recordingId,
        urlExpiresAtEpochMs = urlExpiresAtEpochMs
    )

    @Test
    fun `an entry is only dead-lettered once it has both failed and run out of attempts`() {
        assertFalse(entry(attempts = UploadQueue.MAX_ATTEMPTS).isDeadLettered)
        assertFalse(entry(attempts = 1, lastError = "HTTP 500").isDeadLettered)
        assertTrue(entry(attempts = UploadQueue.MAX_ATTEMPTS, lastError = "HTTP 500").isDeadLettered)
    }

    @Test
    fun `a dead-lettered entry no longer asks the worker for anything`() {
        // The regression this guards: the worker used to retry an exhausted
        // entry forever and bail out of its loop on the failure, so one dead
        // entry at the head of the queue blocked every later recording.
        val dead = entry(attempts = UploadQueue.MAX_ATTEMPTS, lastError = "HTTP 400")
        assertFalse(dead.needsUpload)

        val retryable = entry(attempts = 2, lastError = "HTTP 500")
        assertTrue(retryable.needsUpload)

        assertFalse(entry(uploaded = true).needsUpload)
    }

    @Test
    fun `completeness is decided by the summary text`() {
        assertFalse(entry().isComplete)
        assertFalse(entry(summary = "   ").isComplete)
        assertTrue(entry(summary = "A summary.").isComplete)
    }

    @Test
    fun `a pre-signed url is only reusable while it is live and complete`() {
        val future = System.currentTimeMillis() + 60_000
        val past = System.currentTimeMillis() - 1

        assertTrue(
            entry(uploadUrl = "https://s3/put", recordingId = "rec", urlExpiresAtEpochMs = future)
                .hasUsableUrl
        )
        assertFalse(
            entry(uploadUrl = "https://s3/put", recordingId = "rec", urlExpiresAtEpochMs = past)
                .hasUsableUrl
        )
        // Re-PUTting without the recording id would orphan the upload.
        assertFalse(
            entry(uploadUrl = "https://s3/put", urlExpiresAtEpochMs = future).hasUsableUrl
        )
        assertFalse(entry(recordingId = "rec", urlExpiresAtEpochMs = future).hasUsableUrl)
    }
}
