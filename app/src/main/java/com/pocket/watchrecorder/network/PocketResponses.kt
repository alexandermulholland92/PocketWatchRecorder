package com.pocket.watchrecorder.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shape-tolerant readers for the Pocket public API.
 *
 * Pocket wraps every response in `{"success": ..., "data": {...}}`, mixes
 * snake_case and camelCase, and has been seen returning the same field at
 * different depths across deployments. Rather than model every variant, each
 * lookup searches the whole tree for the first key that resolves.
 *
 * These live at file scope, and `internal` rather than private, precisely so
 * they can be unit tested — this is the most defect-prone code in the app and
 * the only part of it with no Android dependencies.
 */

internal val ID_KEYS = listOf("recording_id", "recordingid", "id", "uuid")

internal val URL_KEYS = listOf(
    "upload_url", "uploadurl", "signed_url", "signedurl",
    "presigned_url", "presignedurl", "url"
)

internal val EXPIRES_KEYS = listOf("expires_in", "expiresin", "expires", "ttl")

/**
 * `state` is the recording's own status. `processing_status` is deliberately
 * excluded — it belongs to the nested `translation` object, and a tree search
 * would happily return that instead.
 */
internal val STATUS_KEYS = listOf("state", "status")

internal val TITLE_KEYS = listOf("recording_title", "recordingtitle", "title", "name")

/**
 * `summarizations` is where the recording-details endpoint puts the generated
 * summary. `content` is the *search* endpoint's field name, kept as a fallback.
 */
internal val SUMMARY_KEYS = listOf(
    "summarizations", "summary", "ai_summary", "aisummary",
    "notes", "content", "content_snippet", "contentsnippet", "markdown"
)

internal val TRANSCRIPT_KEYS = listOf(
    "transcript", "transcript_text", "transcripttext",
    "transcript_segments", "transcriptsegments"
)

internal val ERROR_KEYS = listOf(
    "transcript_error", "summarizations_errors",
    "error_message", "errormessage", "error", "failure_reason"
)

/** Object keys that actually carry prose, in preference order. */
private val TEXT_KEYS = listOf("markdown", "text", "content", "summary", "body", "value")

private val UUID_RE =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val TIMESTAMP_RE =
    Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+(Z|[+-][0-9]{2}:[0-9]{2})$")

/** Shorter than this is a status token, not a summary. */
private const val MIN_PROSE_CHARS = 12

// ---------------------------------------------------------------------------
// Tree search
// ---------------------------------------------------------------------------

/** Depth-first search for the first non-null node under [key]. */
internal fun JsonElement.findNodeByKey(key: String): JsonElement? = when (this) {
    is JsonObject -> entries.firstNotNullOfOrNull { (name, value) ->
        if (name.lowercase().replace('-', '_') == key && value !is JsonNull) value else null
    } ?: entries.firstNotNullOfOrNull { (_, value) -> value.findNodeByKey(key) }

    is JsonArray -> firstNotNullOfOrNull { it.findNodeByKey(key) }
    else -> null
}

internal fun JsonElement.findNode(keys: List<String>): JsonElement? =
    keys.firstNotNullOfOrNull { findNodeByKey(it) }

internal fun JsonElement.findString(
    keys: List<String>,
    predicate: (String) -> Boolean = { true }
): String? = keys.firstNotNullOfOrNull { key ->
    (findNodeByKey(key) as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }
        ?.content
        ?.takeIf(predicate)
}

/** Reads a number that may arrive as a JSON number or as a quoted string. */
internal fun JsonElement.findLong(keys: List<String>): Long? =
    keys.firstNotNullOfOrNull { key ->
        (findNodeByKey(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
    }

/**
 * Like [findNode], but skips a key whose node holds no readable text.
 *
 * Matters because `summarizations` exists from the moment the job is created.
 * Accepting it just for existing meant never falling through to a key that did
 * have the text.
 */
internal fun JsonElement.findProse(keys: List<String>): JsonElement? =
    keys.firstNotNullOfOrNull { key ->
        findNodeByKey(key)?.takeIf { !prose(it).isNullOrBlank() }
    }

/** Compact key outline, so an unexpected shape is legible on a watch. */
internal fun JsonObject.describeKeys(depth: Int = 2): String =
    entries.joinToString(", ") { (key, value) ->
        when {
            value is JsonObject && depth > 0 -> "$key{${value.describeKeys(depth - 1)}}"
            value is JsonArray && depth > 0 -> "$key[]"
            else -> key
        }
    }

// ---------------------------------------------------------------------------
// Prose extraction
// ---------------------------------------------------------------------------

/**
 * Extracts readable text, and nothing else.
 *
 * Earlier this fell back to concatenating every value of an unrecognized
 * object. While `summarizations` holds job metadata rather than a finished
 * summary, that produced a wall of ids, webhook URLs and status tokens — and
 * because the result was non-blank, isReady fired and polling stopped early.
 * Objects are now read only through [TEXT_KEYS]; anything else yields null and
 * we keep waiting.
 */
internal fun prose(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.asProse()
    is JsonArray -> element
        .mapNotNull { prose(it) }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

    is JsonObject -> TEXT_KEYS.firstNotNullOfOrNull { key -> element[key]?.let(::prose) }
}

/** Filters out the scalars that show up in pipeline metadata. */
private fun JsonPrimitive.asProse(): String? {
    if (!isString) return null                      // numbers, booleans, JsonNull
    val value = content.trim()
    return when {
        value.length < MIN_PROSE_CHARS -> null      // "pending", "seed", "false"
        UUID_RE.matches(value) -> null
        TIMESTAMP_RE.matches(value) -> null
        value.startsWith("http://", true) -> null
        value.startsWith("https://", true) -> null
        else -> value
    }
}

// ---------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------

/** Maps the enveloped status payload onto the typed model. */
internal fun JsonObject.toRecordingResponse(): RecordingResponse = RecordingResponse(
    id = findString(ID_KEYS),
    status = findString(STATUS_KEYS),
    title = findString(TITLE_KEYS),
    summary = findProse(SUMMARY_KEYS),
    transcript = findProse(TRANSCRIPT_KEYS),
    errorMessage = findString(ERROR_KEYS)
)

/**
 * Maps the provisioning payload, or explains what came back instead.
 *
 * @throws IllegalArgumentException if no recording id or upload URL is present.
 */
internal fun JsonObject.toProvisionedUpload(): ProvisionedUpload {
    val id = findString(ID_KEYS)
    val url = findString(URL_KEYS) { it.startsWith("http", ignoreCase = true) }

    requireNotNull(id) { "No recording id. Got: ${describeKeys().take(110)}" }
    requireNotNull(url) { "No upload url. Got: ${describeKeys().take(110)}" }

    return ProvisionedUpload(
        recordingId = id,
        uploadUrl = url,
        expiresInSeconds = findLong(EXPIRES_KEYS)?.takeIf { it > 0 }
    )
}
