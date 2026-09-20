package com.pocket.watchrecorder.ui

import android.app.RemoteInput
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Text entry for the API key, using Wear's own input activity.
 *
 * That activity is what offers the on-watch keyboard, voice dictation and
 * handwriting, and on a paired watch it can hand the job to the phone's
 * keyboard — which is the only tolerable way to enter a forty-character key on
 * a wrist.
 *
 * Driven through the platform [RemoteInput] and the intent contract by name,
 * rather than androidx.wear's RemoteInputIntentHelper, to avoid taking on a
 * dependency purely for three string constants.
 */
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"

private const val RESULT_KEY = "pocket_api_key"

private const val TAG = "KeyEntry"

/**
 * Returns a callback that opens the input activity and reports what was typed.
 *
 * [onUnavailable] fires when the device has no such activity, which should not
 * happen on Wear OS but leaves the user with an explanation rather than a
 * button that does nothing.
 */
@Composable
internal fun rememberApiKeyEntry(
    onEntered: (String) -> Unit,
    onUnavailable: () -> Unit = {}
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val typed = RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(RESULT_KEY)
            ?.toString()
            ?.trim()

        if (!typed.isNullOrEmpty()) onEntered(typed)
    }

    return {
        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel("Pocket API key")
            .build()

        val intent = Intent(ACTION_REMOTE_INPUT).apply {
            putExtra(EXTRA_REMOTE_INPUTS, arrayOf(remoteInput))
            putExtra(EXTRA_TITLE, "Pocket API key")
        }

        runCatching { launcher.launch(intent) }
            .onFailure {
                Log.e(TAG, "No remote input activity on this device", it)
                onUnavailable()
            }
    }
}
