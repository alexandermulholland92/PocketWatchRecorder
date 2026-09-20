package com.pocket.watchrecorder.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A key that is present but meaningless is worse than one that is absent: it
 * passes an isNotBlank() check, gets sent, and comes back as an opaque 401.
 * Both of the placeholders this project has actually shipped are covered here.
 */
class ApiKeyTest {

    @Test
    fun `a real key is usable`() {
        assertTrue(isUsableApiKey("pk_7340a1b2c3d4e5f6"))
    }

    @Test
    fun `an absent key is not usable`() {
        assertFalse(isUsableApiKey(""))
        assertFalse(isUsableApiKey("   "))
    }

    @Test
    fun `the placeholders this repo has shipped are not usable`() {
        // What the tracked local.properties contained.
        assertFalse(isUsableApiKey("Your_API_Key_Here"))
        // What the deleted in-source fallback constant contained.
        assertFalse(isUsableApiKey("pk_PASTE_YOUR_KEY_HERE"))
        // What local.properties.example contains.
        assertFalse(isUsableApiKey("pk_your_key_here"))
    }

    @Test
    fun `placeholder detection ignores case and surrounding whitespace`() {
        assertFalse(isUsableApiKey("  YOUR_API_KEY  "))
        assertFalse(isUsableApiKey("paste-your-key"))
    }
}
