package com.pocket.watchrecorder.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Pocket key, encrypted at rest on the watch.
 *
 * This exists so the APK doesn't have to carry a secret. Baking the key in at
 * build time means every build artifact is a credential — you can't publish one
 * from CI, you can't hand one to anyone, and rotating means rebuilding. Entered
 * once on the device instead, the key never leaves it and the APK is just an
 * APK.
 *
 * AES-GCM under a hardware-backed AndroidKeyStore key, written to ordinary
 * SharedPreferences as Base64(iv ‖ ciphertext). Hand-rolled rather than pulled
 * from Jetpack Security: that library's EncryptedSharedPreferences is a thin
 * wrapper over the same primitives, and this avoids a dependency whose
 * maintenance status has been in question.
 */
class ApiKeyStore(context: Context) {

    private companion object {
        const val TAG = "ApiKeyStore"
        const val PREFS_NAME = "pocket-credentials"
        const val PREF_API_KEY = "api_key"

        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "pocket-api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored key, or null if none is set or it can no longer be decrypted. */
    fun read(): String? {
        val stored = prefs.getString(PREF_API_KEY, null) ?: return null

        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            require(packed.size > IV_BYTES) { "ciphertext too short" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES)
            )
            String(
                cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES),
                Charsets.UTF_8
            )
        }.getOrElse {
            // The Keystore key is gone (device restore, credential reset) or the
            // blob is corrupt. Nothing here is recoverable, and keeping it would
            // mean failing this way on every launch.
            Log.w(TAG, "Stored key could not be read; clearing it", it)
            clear()
            null
        }
    }

    /** Replaces the stored key. Returns false if it could not be written. */
    fun write(key: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val ciphertext = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext

        prefs.edit()
            .putString(PREF_API_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()
    }.getOrElse {
        Log.e(TAG, "Could not store the key", it)
        false
    }

    fun clear() {
        prefs.edit().remove(PREF_API_KEY).apply()
    }

    /**
     * The AES key this app encrypts with, created on first use.
     *
     * No user authentication requirement: the upload worker runs while the
     * watch is unattended, and a key it cannot decrypt without the screen on is
     * a key it cannot use.
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
