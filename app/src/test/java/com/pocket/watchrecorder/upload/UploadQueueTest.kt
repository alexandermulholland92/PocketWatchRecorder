package com.pocket.watchrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecordedAtStampTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    /** 2026-09-11 15:30:00 local, i.e. during PDT (UTC-07:00). */
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 11, 15, 30, 0)

    /** What the API demands, and rejected us for not sending. */
    private val rfc3339 = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})$")

    @Test
    fun `the stamp is RFC3339 with a real offset`() {
        // The server's words: "invalid recording_at: must be RFC3339 format".
        // A zone-less wall clock was rejected outright.
        val stamp = recordedAtStamp(0, zone, now)
        assertEquals("2026-09-11T15:30:00-07:00", stamp)
        assertTrue("not RFC3339: $stamp", rfc3339.matches(stamp))
    }

    @Test
    fun `a UTC device still produces a valid zone marker`() {
        val stamp = recordedAtStamp(0, ZoneId.of("UTC"), now)
        assertEquals("2026-09-11T15:30:00Z", stamp)
        assertTrue("not RFC3339: $stamp", rfc3339.matches(stamp))
    }

    @Test
    fun `the stamp is the start of the recording, not its end`() {
        assertEquals(
            "2026-09-11T15:28:00-07:00",
            recordedAtStamp(durationMs = 120_000, zone = zone, now = now)
        )
    }

    @Test
    fun `seconds are always written, even when they are zero`() {
        // LocalDateTime.toString() would drop them, and this parser is strict.
        assertTrue(recordedAtStamp(0, zone, now).contains(":30:00"))
    }

    @Test
    fun `sub-second recordings do not roll back a whole second`() {
        assertEquals(
            "2026-09-11T15:29:59-07:00",
            recordedAtStamp(500, zone, now)
        )
    }
}

class NormalizeRecordedAtTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    @Test
    fun `a timestamp queued by an older build gains the device offset`() {
        // Written as local wall clock, so the offset recovers the real instant
        // rather than inventing one.
        assertEquals(
            "2026-09-11T15:28:00-07:00",
            normalizeRecordedAt("2026-09-11T15:28:00", zone)
        )
    }

    @Test
    fun `a timestamp that already has a zone is left alone`() {
        assertEquals(
            "2026-09-11T15:28:00-07:00",
            normalizeRecordedAt("2026-09-11T15:28:00-07:00", zone)
        )
        assertEquals(
            "2026-09-11T22:28:00Z",
            normalizeRecordedAt("2026-09-11T22:28:00Z", zone)
        )
    }

    @Test
    fun `unparseable text is passed through rather than replaced with a wrong instant`() {
        // Better the server rejects it than we silently claim it happened now.
        assertEquals("not a date", normalizeRecordedAt("not a date", zone))
        assertEquals("", normalizeRecordedAt("   ", zone))
    }
}

class LocalTitleTest {

    @Test
    fun `the title reads in the recording's own local time, not UTC`() {
        // The bug: a correct instant of 2:29 PM PDT was titled by Pocket as
        // "Recording Sep 20, 2026 9:29 PM", because it renders in UTC.
        assertEquals(
            "Recording Sep 20, 2026 2:29 PM",
            localTitleFor("2026-09-20T14:29:00-07:00")
        )
    }

    @Test
    fun `a UTC recording reads as UTC, which is its local time`() {
        assertEquals(
            "Recording Sep 20, 2026 9:29 PM",
            localTitleFor("2026-09-20T21:29:00Z")
        )
    }

    @Test
    fun `the offset in the timestamp decides the title, not the reader's zone`() {
        // Same instant, three offsets: each should read as its own wall clock.
        assertEquals("Recording Sep 20, 2026 2:29 PM", localTitleFor("2026-09-20T14:29:00-07:00"))
        assertEquals("Recording Sep 20, 2026 5:29 PM", localTitleFor("2026-09-20T17:29:00-04:00"))
        assertEquals("Recording Sep 20, 2026 10:29 PM", localTitleFor("2026-09-20T22:29:00+01:00"))
    }

    @Test
    fun `midnight and noon are not rendered as zero`() {
        assertEquals("Recording Sep 20, 2026 12:00 AM", localTitleFor("2026-09-20T00:00:00Z"))
        assertEquals("Recording Sep 20, 2026 12:00 PM", localTitleFor("2026-09-20T12:00:00Z"))
    }

    @Test
    fun `an unparseable timestamp yields no title rather than a wrong one`() {
        // Pocket then falls back to its own naming, which beats a lie.
        assertNull(localTitleFor("2026-09-20T14:29:00"))
        assertNull(localTitleFor("not a date"))
        assertNull(localTitleFor(""))
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
