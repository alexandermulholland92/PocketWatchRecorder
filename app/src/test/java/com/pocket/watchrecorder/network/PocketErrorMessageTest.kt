package com.pocket.watchrecorder.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * A bare "HTTP 403" on the watch cost a long round of diagnosis for something
 * the server had already explained. These pin the explanation being read back.
 */
class PocketErrorMessageTest {

    private fun httpError(code: Int, body: String): HttpException =
        HttpException(
            Response.error<Any>(
                code,
                body.toResponseBody("application/json".toMediaType())
            )
        )

    @Test
    fun `the real 403 from Pocket is read back verbatim`() {
        // Exactly what the API returned for a key issued without upload scope.
        val error = httpError(
            403,
            """{"success":false,"error":"insufficient scope for this operation"}"""
        )
        assertEquals("insufficient scope for this operation", error.pocketErrorMessage())
    }

    @Test
    fun `a body that is not the expected envelope falls back to its text`() {
        val error = httpError(500, "upstream timed out")
        assertEquals("upstream timed out", error.pocketErrorMessage())
    }

    @Test
    fun `an envelope without an error field falls back rather than returning null`() {
        val error = httpError(400, """{"success":false,"detail":"bad request"}""")
        assertTrue(error.pocketErrorMessage().orEmpty().contains("bad request"))
    }

    @Test
    fun `an empty body yields nothing to show`() {
        assertNull(httpError(403, "").pocketErrorMessage())
    }

    @Test
    fun `whitespace is collapsed so it fits on a watch`() {
        val error = httpError(400, "{\"error\":\"line one\\n\\n   line two\"}")
        assertEquals("line one line two", error.pocketErrorMessage())
    }

    @Test
    fun `a long explanation is truncated`() {
        val long = "x".repeat(300)
        val message = httpError(400, """{"error":"$long"}""").pocketErrorMessage()
        assertEquals(70, message?.length)
    }

    @Test
    fun `html error pages do not blow up the parser`() {
        val error = httpError(502, "<html><body>Bad Gateway</body></html>")
        assertTrue(error.pocketErrorMessage().orEmpty().contains("Bad Gateway"))
    }
}
