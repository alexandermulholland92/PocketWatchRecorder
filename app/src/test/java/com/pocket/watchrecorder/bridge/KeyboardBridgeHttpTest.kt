package com.pocket.watchrecorder.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-rolled HTTP is exactly the sort of thing that works against one browser
 * and falls over against another, so the parsing is pinned here.
 */
class KeyboardBridgeHttpTest {

    // -----------------------------------------------------------------------
    // Form decoding
    // -----------------------------------------------------------------------

    @Test
    fun `a normal submission decodes`() {
        val form = parseFormBody("pin=1234&text=pk_abcdef123456")
        assertEquals("1234", form["pin"])
        assertEquals("pk_abcdef123456", form["text"])
    }

    @Test
    fun `percent escapes and plus signs decode`() {
        // A browser sends spaces as '+' and escapes everything else.
        val form = parseFormBody("text=hello+world%20%26+co%3D1")
        assertEquals("hello world & co=1", form["text"])
    }

    @Test
    fun `a value containing an equals sign survives`() {
        // Base64-ish keys end in '=' padding; splitting on every '=' would eat it.
        val form = parseFormBody("text=YWJjZGVm%3D%3D")
        assertEquals("YWJjZGVm==", form["text"])
    }

    @Test
    fun `empty and malformed pairs are skipped rather than crashing`() {
        val form = parseFormBody("&&pin=1234&novalue&=orphan&")
        assertEquals("1234", form["pin"])
        assertEquals("", form["novalue"])
    }

    @Test
    fun `an empty body is an empty map`() {
        assertTrue(parseFormBody("").isEmpty())
    }

    // -----------------------------------------------------------------------
    // Request line
    // -----------------------------------------------------------------------

    @Test
    fun `request lines parse`() {
        assertEquals("GET" to "/", parseRequestLine("GET / HTTP/1.1"))
        assertEquals("POST" to "/submit", parseRequestLine("POST /submit HTTP/1.1"))
        // Method case is normalised; some clients are creative.
        assertEquals("POST" to "/submit", parseRequestLine("post /submit HTTP/1.0"))
    }

    @Test
    fun `junk request lines are rejected rather than half-parsed`() {
        assertNull(parseRequestLine(""))
        assertNull(parseRequestLine("GARBAGE"))
    }

    // -----------------------------------------------------------------------
    // Content-Length
    // -----------------------------------------------------------------------

    @Test
    fun `content length is read case-insensitively`() {
        assertEquals(42, contentLengthOf(listOf("Host: x", "Content-Length: 42")))
        assertEquals(42, contentLengthOf(listOf("content-length:42")))
    }

    @Test
    fun `a missing or unparseable content length reads as zero`() {
        assertEquals(0, contentLengthOf(listOf("Host: x")))
        assertEquals(0, contentLengthOf(listOf("Content-Length: banana")))
    }

    @Test
    fun `an absurd content length is clamped instead of allocating it`() {
        // Otherwise a hostile or broken client sizes a CharArray on a watch.
        assertEquals(MAX_BODY_BYTES, contentLengthOf(listOf("Content-Length: 999999999")))
        assertEquals(0, contentLengthOf(listOf("Content-Length: -5")))
    }

    // -----------------------------------------------------------------------
    // Responses
    // -----------------------------------------------------------------------

    @Test
    fun `responses carry a byte-accurate content length`() {
        // "£" is two bytes in UTF-8 but one character; a length in characters
        // truncates the body and hangs the browser.
        val body = "£10"
        val response = httpResponse("200 OK", "text/html", body)
        assertTrue(response.contains("Content-Length: 4"))
        assertTrue(response.contains("Connection: close"))
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("\r\n\r\n"))
    }

    @Test
    fun `echoed text cannot inject markup`() {
        val escaped = escapeHtml("<script>alert(\"x\")</script> & co")
        assertEquals("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; co", escaped)
    }

    @Test
    fun `the form posts back to submit and asks for both fields`() {
        val page = formPage("Type on the watch")
        assertTrue(page.contains("action=\"/submit\""))
        assertTrue(page.contains("name=\"pin\""))
        assertTrue(page.contains("name=\"text\""))
        // Autocorrect on a key would be ruinous.
        assertTrue(page.contains("autocapitalize=\"none\""))
        assertTrue(page.contains("spellcheck=\"false\""))
    }

    @Test
    fun `a message shown on the form is escaped`() {
        val page = formPage("Type", "<b>nope</b>")
        assertTrue(page.contains("&lt;b&gt;nope&lt;/b&gt;"))
    }
}
