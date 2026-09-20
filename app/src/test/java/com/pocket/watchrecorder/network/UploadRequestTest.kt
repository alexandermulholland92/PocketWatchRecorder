package com.pocket.watchrecorder.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provisioning body and the S3 error it can provoke.
 *
 * The content type is the one field both ends have to agree on: a pre-signed
 * URL can bind it into its signature, and a mismatch comes back as a bare 403.
 * It was previously not sent at all while the PUT set a header regardless.
 */
class UploadRequestTest {

    /** Mirrors PocketClient's configuration, explicitNulls included. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `the request carries the content type under its wire name`() {
        val body = json.encodeToString(
            UploadUrlRequest(
                fileName = "watch_20260920.m4a",
                title = "Recording Sep 20, 2026 2:29 PM",
                duration = 42,
                recordingAt = "2026-09-20T14:29:00-07:00",
                contentType = "audio/m4a"
            )
        )

        assertTrue(body, body.contains("\"content_type\":\"audio/m4a\""))
        assertTrue(body, body.contains("\"file_name\":\"watch_20260920.m4a\""))
        assertTrue(body, body.contains("\"recording_at\":\"2026-09-20T14:29:00-07:00\""))
    }

    @Test
    fun `absent optional fields are omitted rather than sent as null`() {
        // A null title is what lets Pocket name the recording itself; sending
        // an explicit null is not the same request.
        val body = json.encodeToString(UploadUrlRequest(fileName = "a.m4a"))

        assertEquals("""{"file_name":"a.m4a"}""", body)
        assertFalse(body.contains("null"))
    }
}

class S3ErrorCodeTest {

    @Test
    fun `the code is pulled out of an S3 error body`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error><Code>SignatureDoesNotMatch</Code>
            <Message>The request signature we calculated does not match.</Message>
            <RequestId>ABC123</RequestId></Error>
        """.trimIndent()

        assertEquals("SignatureDoesNotMatch", s3ErrorCode(body))
    }

    @Test
    fun `the message it produces stays short enough to read on a watch`() {
        val failure = S3UploadException(403, "SignatureDoesNotMatch")
        assertEquals("S3 403: SignatureDoesNotMatch", failure.message)
        assertTrue((failure.message?.length ?: 0) < 40)
    }

    @Test
    fun `an unrecognisable body still yields a usable message`() {
        assertNull(s3ErrorCode("<html>502 Bad Gateway</html>"))
        assertNull(s3ErrorCode(""))
        assertEquals("S3 upload failed (HTTP 500)", S3UploadException(500, null).message)
    }

    @Test
    fun `an absurdly long code is not accepted as one`() {
        // Guards against swallowing a whole document between two tags.
        assertNull(s3ErrorCode("<Code>" + "x".repeat(200) + "</Code>"))
    }
}
