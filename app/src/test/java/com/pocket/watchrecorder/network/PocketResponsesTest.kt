package com.pocket.watchrecorder.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pocket public API is undocumented, inconsistently shaped, and the source
 * of every parsing bug this app has had. These fixtures are the shapes that
 * caused them.
 */
class PocketResponsesTest {

    private fun json(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    // -----------------------------------------------------------------------
    // prose()
    // -----------------------------------------------------------------------

    @Test
    fun `prose reads a bare string`() {
        assertEquals(
            "We agreed to ship on Friday.",
            prose(json("""{"s":"We agreed to ship on Friday."}""")["s"])
        )
    }

    @Test
    fun `prose rejects the scalars that show up in pipeline metadata`() {
        // Too short to be a summary.
        assertNull(prose(json("""{"s":"pending"}""")["s"]))
        // A bare id.
        assertNull(prose(json("""{"s":"3f2504e0-4f89-11d3-9a0c-0305e82c3301"}""")["s"]))
        // A timestamp.
        assertNull(prose(json("""{"s":"2026-09-11T15:28:00Z"}""")["s"]))
        // A webhook URL.
        assertNull(prose(json("""{"s":"https://example.com/webhook/12345"}""")["s"]))
        // Numbers and booleans are not prose.
        assertNull(prose(json("""{"n":123456789}""")["n"]))
        assertNull(prose(json("""{"b":true}""")["b"]))
    }

    @Test
    fun `prose reads an object only through known text keys`() {
        val withText = json("""{"o":{"markdown":"A summary long enough to count."}}""")
        assertEquals("A summary long enough to count.", prose(withText["o"]))

        // The regression: an unrecognized object used to be flattened into a
        // wall of ids and status tokens, which then read as a finished summary.
        val metadataOnly = json(
            """{"o":{"job_id":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","state":"processing"}}"""
        )
        assertNull(prose(metadataOnly["o"]))
    }

    @Test
    fun `prose joins the readable parts of an array`() {
        val payload = json(
            """{"a":[{"text":"First paragraph of the summary."},
                     {"state":"done"},
                     {"text":"Second paragraph of the summary."}]}"""
        )
        assertEquals(
            "First paragraph of the summary.\n\nSecond paragraph of the summary.",
            prose(payload["a"])
        )
    }

    // -----------------------------------------------------------------------
    // Tree search
    // -----------------------------------------------------------------------

    @Test
    fun `findString searches through the success-data envelope`() {
        val payload = json("""{"success":true,"data":{"recording_id":"rec_123"}}""")
        assertEquals("rec_123", payload.findString(ID_KEYS))
    }

    @Test
    fun `findString honours key priority over depth`() {
        // "id" sits shallower, but "recording_id" is the preferred key.
        val payload = json("""{"id":"envelope","data":{"recording_id":"rec_123"}}""")
        assertEquals("rec_123", payload.findString(ID_KEYS))
    }

    @Test
    fun `findString ignores nulls and blanks`() {
        val payload = json("""{"data":{"recording_id":null,"id":"  ","uuid":"rec_9"}}""")
        assertEquals("rec_9", payload.findString(ID_KEYS))
    }

    @Test
    fun `findString applies the predicate, so a filename is not read as a URL`() {
        val payload = json("""{"data":{"url":"watch_20260911.m4a","upload_url":"https://s3/put"}}""")
        assertEquals(
            "https://s3/put",
            payload.findString(URL_KEYS) { it.startsWith("http", ignoreCase = true) }
        )
    }

    @Test
    fun `findLong reads a number or a quoted number`() {
        assertEquals(900L, json("""{"data":{"expires_in":900}}""").findLong(EXPIRES_KEYS))
        assertEquals(900L, json("""{"data":{"expires_in":"900"}}""").findLong(EXPIRES_KEYS))
        assertNull(json("""{"data":{}}""").findLong(EXPIRES_KEYS))
    }

    @Test
    fun `status does not pick up the nested translation processing_status`() {
        val payload = json(
            """{"data":{"state":"processing","translation":{"processing_status":"completed"}}}"""
        )
        assertEquals("processing", payload.toRecordingResponse().normalizedStatus)
    }

    @Test
    fun `describeKeys outlines an unexpected shape`() {
        val payload = json("""{"success":true,"data":{"items":[1,2],"meta":{"page":1}}}""")
        assertEquals("success, data{items[], meta{page}}", payload.describeKeys())
    }

    // -----------------------------------------------------------------------
    // Provisioning
    // -----------------------------------------------------------------------

    @Test
    fun `provisioning accepts both documented field shapes`() {
        val snake = json(
            """{"data":{"recording_id":"rec_1","upload_url":"https://s3/put","expires_in":900}}"""
        ).toProvisionedUpload()
        assertEquals("rec_1", snake.recordingId)
        assertEquals("https://s3/put", snake.uploadUrl)
        assertEquals(900L, snake.expiresInSeconds)

        val short = json("""{"id":"rec_2","url":"https://s3/other"}""").toProvisionedUpload()
        assertEquals("rec_2", short.recordingId)
        assertEquals("https://s3/other", short.uploadUrl)
        // Absent expiry falls back to the default lifetime at the call site.
        assertNull(short.expiresInSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `provisioning without an upload url is rejected`() {
        json("""{"data":{"recording_id":"rec_1"}}""").toProvisionedUpload()
    }

    @Test
    fun `provisioning failure names the keys that did come back`() {
        val error = runCatching { json("""{"success":false,"detail":"nope"}""").toProvisionedUpload() }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("success, detail"))
    }

    // -----------------------------------------------------------------------
    // Recording status
    // -----------------------------------------------------------------------

    @Test
    fun `an empty summarizations block is not treated as ready`() {
        // This is the bug that stopped polling early: `summarizations` exists
        // from the moment the job is created, holding only job metadata.
        val payload = json(
            """
            {"data":{
              "state":"processing",
              "recording_title":"Standup",
              "summarizations":[{"id":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","status":"queued"}]
            }}
            """
        )
        val recording = payload.toRecordingResponse()

        assertNull(recording.summaryText)
        assertFalse(recording.isReady)
        assertEquals("Standup", recording.title)
    }

    @Test
    fun `summarizations with real text wins`() {
        val payload = json(
            """
            {"data":{
              "state":"completed",
              "summarizations":[{"markdown":"Team agreed to ship on Friday."}]
            }}
            """
        )
        val recording = payload.toRecordingResponse()

        assertEquals("Team agreed to ship on Friday.", recording.summaryText)
        assertTrue(recording.isReady)
    }

    @Test
    fun `a metadata-only summarizations block falls through to a later key`() {
        val payload = json(
            """
            {"data":{
              "state":"processing",
              "summarizations":[{"id":"3f2504e0-4f89-11d3-9a0c-0305e82c3301"}],
              "notes":"The notes field carried the text this time."
            }}
            """
        )
        assertEquals(
            "The notes field carried the text this time.",
            payload.toRecordingResponse().summaryText
        )
    }

    @Test
    fun `text arriving before a terminal state still counts as ready`() {
        val payload = json(
            """{"data":{"state":"processing","summary":"Enough text to be a summary."}}"""
        )
        assertTrue(payload.toRecordingResponse().isReady)
    }

    @Test
    fun `failed states are recognised and carry their reason`() {
        for (state in listOf("failed", "ERROR", "errored", "rejected", "cancelled")) {
            val payload = json("""{"data":{"state":"$state","error_message":"Audio too quiet"}}""")
            val recording = payload.toRecordingResponse()
            assertTrue("$state should be failed", recording.isFailed)
            assertEquals("Audio too quiet", recording.errorMessage)
        }
    }

    @Test
    fun `an absent status is treated as still processing`() {
        val recording = json("""{"data":{"recording_id":"rec_1"}}""").toRecordingResponse()
        assertEquals("processing", recording.normalizedStatus)
        assertFalse(recording.isReady)
        assertFalse(recording.isFailed)
    }

    @Test
    fun `a transcript is readable even when no summary arrives`() {
        val payload = json(
            """{"data":{"state":"processing","transcript":{"text":"So the first thing is."}}}"""
        )
        val recording = payload.toRecordingResponse()
        assertNull(recording.summaryText)
        assertEquals("So the first thing is.", recording.transcriptText)
    }
}
