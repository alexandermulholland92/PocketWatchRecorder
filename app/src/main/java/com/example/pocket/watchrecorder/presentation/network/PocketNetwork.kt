package com.pocket.watchrecorder.network

import com.pocket.watchrecorder.BuildConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import retrofit2.Converter
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Hardcoded literal. This is a fixed endpoint, never environment-specific, so
 * routing it through BuildConfig only added a way for it to arrive empty.
 * The trailing slash is required — Retrofit rejects a baseUrl without one.
 */
private const val BASE_URL = "https://public.heypocketai.com/"

/**
 * Paste your key here if the Gradle injection isn't working.
 *
 * BuildConfig wins when it has a value, so restoring the local.properties
 * route later needs no code change. Treat this file as secret while a literal
 * key is present: don't commit it, and rotate the key if it leaks.
 */
private const val API_KEY_FALLBACK = "Your_API_Key_Here"

val API_KEY: String = BuildConfig.POCKET_API_KEY
    .ifBlank { API_KEY_FALLBACK }
    .removePrefix("Bearer ")
    .trim()

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
data class UploadUrlRequest(
    @SerialName("file_name") val fileName: String,
    val title: String,
    /** Length in seconds. Documented field; omitting it may leave work unqueued. */
    val duration: Long? = null,
    /** ISO-8601 instant the audio was captured. */
    @SerialName("recording_at") val recordingAt: String? = null
)

/**
 * The provisioning response. Field names are modelled defensively: different
 * deployments of the Pocket public API have been seen returning either
 * `recording_id`/`upload_url` or `id`/`url`, so both are accepted and resolved
 * through [resolvedRecordingId] / [resolvedUploadUrl].
 */
@Serializable
data class UploadUrlResponse(
    @SerialName("recording_id") val recordingId: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    val id: String? = null,
    val url: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
) {
    val resolvedRecordingId: String?
        get() = recordingId ?: id

    val resolvedUploadUrl: String?
        get() = uploadUrl ?: url
}

/**
 * The recording/status response. `summary`, `notes` and `transcript` are typed
 * as raw [JsonElement] because the pipeline returns them as a bare string in
 * some states and as a nested object/array in others. [summaryText] flattens
 * whichever shape came back.
 */
@Serializable
data class RecordingResponse(
    val id: String? = null,
    @SerialName("recording_id") val recordingId: String? = null,
    val status: String? = null,
    val title: String? = null,
    val summary: JsonElement? = null,
    val notes: JsonElement? = null,
    val transcript: JsonElement? = null,
    @SerialName("error_message") val errorMessage: String? = null
) {
    val normalizedStatus: String
        get() = (status ?: "processing").lowercase()

    val isFailed: Boolean
        get() = normalizedStatus in FAILED_STATES

    /** True once the pipeline is finished, or once usable text has appeared. */
    val isReady: Boolean
        get() = normalizedStatus in READY_STATES || !summaryText.isNullOrBlank()

    val summaryText: String?
        get() = flatten(summary) ?: flatten(notes)

    val transcriptText: String?
        get() = flatten(transcript)

    private companion object {
        val READY_STATES = setOf("completed", "complete", "ready", "done", "processed", "success")
        val FAILED_STATES = setOf("failed", "error", "errored", "rejected", "cancelled")

        /** Walks the common summary shapes down to plain text. */
        fun flatten(element: JsonElement?): String? = when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNullSafe()
            is JsonArray -> element
                .mapNotNull { flatten(it) }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }

            is JsonObject -> {
                val direct = listOf("text", "content", "summary", "body", "markdown", "value")
                    .firstNotNullOfOrNull { key -> element[key]?.let(::flatten) }
                direct ?: element.values
                    .mapNotNull { flatten(it) }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .takeIf { it.isNotBlank() }
            }

            else -> null
        }

        /** JsonNull is a JsonPrimitive whose unquoted content is literally "null". */
        fun JsonPrimitive.contentOrNullSafe(): String? =
            if (!isString && content == "null") null
            else content.takeIf { it.isNotBlank() }
    }
}

/** Thrown when the pipeline itself reports a failure (as opposed to transport). */
class PocketPipelineException(message: String) : IOException(message)

// ---------------------------------------------------------------------------
// Retrofit API
// ---------------------------------------------------------------------------

interface PocketApi {

    @POST("api/v1/public/recordings/upload-url")
    suspend fun createUploadUrl(@Body body: UploadUrlRequest): JsonObject

    @GET("api/v1/public/recordings/{recordingId}")
    suspend fun getRecording(@Path("recordingId") recordingId: String): JsonObject
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

object PocketClient {

    private const val UPLOAD_CHUNK_BYTES = 32L * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Shared connection pool / dispatcher between the two clients. */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Talks to Pocket. Carries the bearer token. */
    private val apiClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(AuthInterceptor)
            .build()
    }

    /**
     * Talks to S3. Deliberately does NOT carry the Authorization header — a
     * pre-signed URL already encodes its own credentials and S3 rejects the
     * request with 400 InvalidArgument if a second auth mechanism is present.
     */
    private val s3Client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Minimal kotlinx-serialization <-> Retrofit bridge.
     *
     * Hand-rolled deliberately: it depends only on Retrofit and the
     * serialization runtime that are already present, so there is no third
     * artifact whose version has to stay aligned with them.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private class JsonConverterFactory(private val json: Json) : Converter.Factory() {

        private val mediaType = "application/json; charset=utf-8".toMediaType()

        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *> {
            val loader = json.serializersModule.serializer(type)
            return Converter<ResponseBody, Any?> { body ->
                body.use { json.decodeFromString(loader, it.string()) }
            }
        }

        override fun requestBodyConverter(
            type: Type,
            parameterAnnotations: Array<out Annotation>,
            methodAnnotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<*, RequestBody> {
            val saver = json.serializersModule.serializer(type)
            return Converter<Any?, RequestBody> { value ->
                json.encodeToString(saver, value).toRequestBody(mediaType)
            }
        }
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(apiClient)
            .addConverterFactory(JsonConverterFactory(json))
            .build()
    }

    val api: PocketApi by lazy { retrofit.create(PocketApi::class.java) }

    private object AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $API_KEY")
                .header("Accept", "application/json")
                .build()
            return chain.proceed(request)
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: provision
    // -----------------------------------------------------------------------

    suspend fun createUpload(
        fileName: String,
        title: String,
        durationSeconds: Long? = null,
        recordedAt: String? = null
    ): UploadUrlResponse {
        check(API_KEY.isNotBlank() && !API_KEY.startsWith("pk_PASTE")) {
            "Pocket API key is not set"
        }
        val raw = api.createUploadUrl(
            UploadUrlRequest(
                fileName = fileName,
                title = title,
                duration = durationSeconds,
                recordingAt = recordedAt
            )
        )

        // The field names and nesting depth of this response aren't documented,
        // so search the whole tree rather than assuming a flat shape.
        val id = raw.findString(ID_KEYS)
        val url = raw.findString(URL_KEYS) { it.startsWith("http", ignoreCase = true) }

        requireNotNull(id) { "No recording id. Got: ${raw.describeKeys().take(110)}" }
        requireNotNull(url) { "No upload url. Got: ${raw.describeKeys().take(110)}" }

        return UploadUrlResponse(recordingId = id, uploadUrl = url)
    }

    // Pocket wraps every response in {"success": ..., "data": {...}} and uses
    // camelCase field names, so each lookup searches the whole tree. Key lists
    // are in priority order: the first one that resolves anywhere wins.

    private val ID_KEYS = listOf("recording_id", "recordingid", "id", "uuid")

    private val URL_KEYS = listOf(
        "upload_url", "uploadurl", "signed_url", "signedurl",
        "presigned_url", "presignedurl", "url"
    )

    /**
     * `state` is the recording's own status. `processing_status` is deliberately
     * excluded — it belongs to the nested `translation` object, and a tree search
     * would happily return that instead.
     */
    private val STATUS_KEYS = listOf("state", "status")

    private val TITLE_KEYS = listOf("recording_title", "recordingtitle", "title", "name")

    /**
     * `summarizations` is where the recording-details endpoint puts the generated
     * summary. `content` is the *search* endpoint's field name, kept as a fallback.
     */
    private val SUMMARY_KEYS = listOf(
        "summarizations", "summary", "ai_summary", "aisummary",
        "notes", "content", "content_snippet", "contentsnippet", "markdown"
    )

    private val TRANSCRIPT_KEYS = listOf(
        "transcript", "transcript_text", "transcripttext",
        "transcript_segments", "transcriptsegments"
    )

    private val ERROR_KEYS = listOf(
        "transcript_error", "summarizations_errors",
        "error_message", "errormessage", "error", "failure_reason"
    )

    /** Depth-first search for the first non-null node under [key]. */
    private fun JsonElement.findNodeByKey(key: String): JsonElement? = when (this) {
        is JsonObject -> entries.firstNotNullOfOrNull { (name, value) ->
            if (name.lowercase().replace('-', '_') == key && value !is JsonNull) value else null
        } ?: entries.firstNotNullOfOrNull { (_, value) -> value.findNodeByKey(key) }

        is JsonArray -> firstNotNullOfOrNull { it.findNodeByKey(key) }
        else -> null
    }

    private fun JsonElement.findNode(keys: List<String>): JsonElement? =
        keys.firstNotNullOfOrNull { findNodeByKey(it) }

    private fun JsonElement.findString(
        keys: List<String>,
        predicate: (String) -> Boolean = { true }
    ): String? = keys.firstNotNullOfOrNull { key ->
        (findNodeByKey(key) as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() }
            ?.content
            ?.takeIf(predicate)
    }

    /** Compact key outline, so an unexpected shape is legible on a watch. */
    private fun JsonObject.describeKeys(depth: Int = 2): String =
        entries.joinToString(", ") { (key, value) ->
            when {
                value is JsonObject && depth > 0 -> "$key{${value.describeKeys(depth - 1)}}"
                value is JsonArray && depth > 0 -> "$key[]"
                else -> key
            }
        }

    /** Maps the enveloped status payload onto the typed model. */
    private fun JsonObject.toRecordingResponse(): RecordingResponse = RecordingResponse(
        id = findString(ID_KEYS),
        status = findString(STATUS_KEYS),
        title = findString(TITLE_KEYS),
        summary = findNode(SUMMARY_KEYS),
        transcript = findNode(TRANSCRIPT_KEYS),
        errorMessage = findString(ERROR_KEYS)
    )

    // -----------------------------------------------------------------------
    // Step 3: S3 PUT
    // -----------------------------------------------------------------------

    /**
     * Streams [file] to the pre-signed [uploadUrl] with a raw OkHttp PUT.
     *
     * [contentType] must be byte-identical to the content_type sent during
     * provisioning — it is part of the signature, and a mismatch surfaces as a
     * 403 SignatureDoesNotMatch rather than anything more descriptive.
     *
     * Cancelling the calling coroutine cancels the in-flight HTTP call.
     */
    suspend fun uploadRecording(
        uploadUrl: String,
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit = {}
    ): Unit = suspendCancellableCoroutine { continuation ->
        val body = ProgressRequestBody(file, contentType.toMediaType(), onProgress)

        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .header("Content-Type", contentType)
            .build()

        val call = s3Client.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!continuation.isActive) return
                    if (res.isSuccessful) {
                        onProgress(1f)
                        continuation.resume(Unit)
                    } else {
                        val detail = runCatching { res.body?.string().orEmpty() }
                            .getOrDefault("")
                            .take(240)
                        continuation.resumeWithException(
                            IOException("S3 upload failed (HTTP ${res.code}) $detail".trim())
                        )
                    }
                }
            }
        })
    }

    /** Streams the file in chunks so the watch UI can show real upload progress. */
    private class ProgressRequestBody(
        private val file: File,
        private val type: MediaType?,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {

        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength().coerceAtLeast(1L)
            var sent = 0L
            var lastReportedPercent = -1

            file.source().use { source ->
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, UPLOAD_CHUNK_BYTES)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sent += read

                    // Throttle emissions to whole percentage points so we don't
                    // flood the UI StateFlow on a small-heap watch process.
                    val percent = ((sent * 100) / total).toInt()
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress((percent / 100f).coerceIn(0f, 1f))
                    }
                }
                sink.flush()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Step 4: poll for the summary
    // -----------------------------------------------------------------------

    /**
     * Polls `GET /recordings/{id}` with capped exponential backoff until the
     * recording reports a terminal state or [timeoutMillis] elapses.
     *
     * A 404/409/425 immediately after upload is treated as "not indexed yet"
     * rather than a hard failure — S3 write visibility and Pocket's ingest can
     * race by a couple of seconds.
     *
     * Throws [kotlinx.coroutines.TimeoutCancellationException] on timeout.
     */
    suspend fun awaitProcessing(
        recordingId: String,
        timeoutMillis: Long = 6 * 60 * 1_000L,
        onStatus: (String) -> Unit = {}
    ): RecordingResponse {
        var lastSeen: RecordingResponse? = null

        return try {
            pollUntilReady(recordingId, timeoutMillis, onStatus) { lastSeen = it }
        } catch (timeout: TimeoutCancellationException) {
            // Summarization can be disabled on the account, in which case
            // `summarizations` never populates. A transcript is still a result
            // worth showing rather than failing the whole run.
            lastSeen?.takeIf { !it.transcriptText.isNullOrBlank() } ?: throw timeout
        }
    }

    private suspend fun pollUntilReady(
        recordingId: String,
        timeoutMillis: Long,
        onStatus: (String) -> Unit,
        onSnapshot: (RecordingResponse) -> Unit
    ): RecordingResponse = withTimeout(timeoutMillis) {
        var backoffMillis = 2_000L
        var result: RecordingResponse? = null

        while (result == null) {
            val snapshot: RecordingResponse? = try {
                api.getRecording(recordingId).toRecordingResponse()
            } catch (http: HttpException) {
                when (http.code()) {
                    404, 409, 425 -> null            // still landing; keep waiting
                    401, 403 -> throw PocketPipelineException("Authorization rejected (HTTP ${http.code()})")
                    else -> throw http
                }
            }

            if (snapshot != null) {
                onSnapshot(snapshot)
                onStatus(snapshot.normalizedStatus)

                if (snapshot.isFailed) {
                    throw PocketPipelineException(
                        snapshot.errorMessage ?: "Processing failed (${snapshot.normalizedStatus})"
                    )
                }
                if (snapshot.isReady) {
                    result = snapshot
                    break
                }
            } else {
                onStatus("queued")
            }

            delay(backoffMillis)
            backoffMillis = (backoffMillis * 3 / 2).coerceAtMost(15_000L)
        }

        // The loop only exits once `result` is set, but the compiler can't
        // smart-cast a var across a `break`, so assert it explicitly.
        checkNotNull(result) { "Poll loop exited without a recording" }
    }
}
