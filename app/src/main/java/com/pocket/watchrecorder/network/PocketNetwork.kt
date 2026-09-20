package com.pocket.watchrecorder.network

import com.pocket.watchrecorder.BuildConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
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
 * A key baked in at build time from `local.properties` or the POCKET_API_KEY
 * environment variable.
 *
 * Optional, and only a fallback now: it ships in the APK as a plaintext
 * constant, so any build that carries one is itself a credential. Prefer
 * entering the key on the watch, which keeps it off every artifact.
 *
 * There is deliberately no in-source fallback constant: the one that used to
 * live here invited pasting a live key into a tracked file, which is exactly
 * how this repository leaked one.
 */
private val BUILD_TIME_API_KEY: String = BuildConfig.POCKET_API_KEY
    .removePrefix("Bearer ")
    .trim()

/**
 * Text that means "nobody filled this in".
 *
 * A blank key is not the only way to end up without one: copying
 * local.properties.example without editing it, or a checkout whose key was
 * replaced with a placeholder before being committed, both produce a non-blank
 * string that sails through an isNotBlank() check and is then rejected by the
 * server as an opaque 401.
 */
private val PLACEHOLDER_KEY_MARKERS = listOf(
    "paste", "your_api_key", "your-api-key", "your key", "your_key", "yourkey", "xxx"
)

/** Whether [key] is something worth sending to Pocket at all. */
internal fun isUsableApiKey(key: String): Boolean {
    val normalized = key.trim().lowercase()
    return normalized.isNotEmpty() && PLACEHOLDER_KEY_MARKERS.none { normalized.contains(it) }
}

/**
 * Where the Pocket key comes from at runtime.
 *
 * A key entered on the watch wins over one baked into the build. That ordering
 * is what lets a published APK carry no secret at all while a locally built one
 * keeps working exactly as before.
 */
object PocketCredentials {

    @Volatile
    private var store: ApiKeyStore? = null

    @Volatile
    private var entered: String? = null

    /** Called once, from [com.pocket.watchrecorder.PocketApplication]. */
    fun bind(keyStore: ApiKeyStore) {
        store = keyStore
        entered = keyStore.read()
    }

    /** Stores [key] on the device. Returns false if it could not be written. */
    fun set(key: String): Boolean {
        val trimmed = key.trim().removePrefix("Bearer ").trim()
        val written = store?.write(trimmed) ?: false
        if (written) entered = trimmed
        return written
    }

    fun clear() {
        store?.clear()
        entered = null
    }

    /** True when a key was entered on this watch, as opposed to built in. */
    val isDeviceKey: Boolean
        get() = isUsableApiKey(entered.orEmpty())

    val current: String
        get() = entered?.takeIf { isUsableApiKey(it) } ?: BUILD_TIME_API_KEY

    val isConfigured: Boolean
        get() = isUsableApiKey(current)
}

internal val isApiKeyConfigured: Boolean
    get() = PocketCredentials.isConfigured

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
data class UploadUrlRequest(
    @SerialName("file_name") val fileName: String,
    /**
     * Left null so Pocket titles the recording from its own transcript. The
     * Json config uses explicitNulls = false, so a null title is omitted from
     * the request body entirely rather than sent as an empty string.
     */
    val title: String? = null,
    /** Length in seconds. Documented field; omitting it may leave work unqueued. */
    val duration: Long? = null,
    /** ISO-8601 instant the audio was captured. */
    @SerialName("recording_at") val recordingAt: String? = null,
    /**
     * The media type the audio will be PUT with.
     *
     * Sent so the two ends cannot disagree. A pre-signed URL can bind the
     * content type into its signature, in which case a PUT carrying a
     * different one fails as 403 SignatureDoesNotMatch — an error that says
     * nothing about its own cause. This field was previously not sent at all
     * while the PUT set a header regardless, so the agreement the upload
     * depends on was left to chance.
     */
    @SerialName("content_type") val contentType: String? = null
)

/**
 * A provisioned upload slot.
 *
 * Built by [toProvisionedUpload] from a tree search rather than by
 * deserialization: the field names and nesting depth of the provisioning
 * response vary between Pocket deployments.
 */
data class ProvisionedUpload(
    val recordingId: String,
    val uploadUrl: String,
    /** Lifetime of [uploadUrl], when the API reports one. */
    val expiresInSeconds: Long? = null
)

/**
 * The recording/status response.
 *
 * `summary` and `transcript` are raw [JsonElement] because the pipeline returns
 * them as a bare string in some states and as a nested object/array in others;
 * [summaryText] flattens whichever shape came back. Built by hand from
 * [toRecordingResponse], so it needs no serializer of its own.
 */
data class RecordingResponse(
    val id: String? = null,
    val status: String? = null,
    val title: String? = null,
    val summary: JsonElement? = null,
    val transcript: JsonElement? = null,
    val errorMessage: String? = null
) {
    val normalizedStatus: String
        get() = (status ?: "processing").lowercase()

    val isFailed: Boolean
        get() = normalizedStatus in FAILED_STATES

    /** True once the pipeline is finished, or once usable text has appeared. */
    val isReady: Boolean
        get() = normalizedStatus in READY_STATES || !summaryText.isNullOrBlank()

    val summaryText: String?
        get() = prose(summary)

    val transcriptText: String?
        get() = prose(transcript)

    companion object {
        private val READY_STATES =
            setOf("completed", "complete", "ready", "done", "processed", "success")
        private val FAILED_STATES =
            setOf("failed", "error", "errored", "rejected", "cancelled")
    }
}

/** Thrown when the pipeline itself reports a failure (as opposed to transport). */
class PocketPipelineException(message: String) : IOException(message)

/**
 * Pocket's own explanation for a failed call, if it gave one.
 *
 * Worth surfacing: "HTTP 403" on its own sent someone round a long diagnostic
 * loop that the server had already answered with "insufficient scope for this
 * operation". Errors arrive as `{"success":false,"error":"..."}`; anything else
 * falls back to the raw body.
 *
 * This is the server's message and nothing else. An earlier version of this
 * code appended the API key's first and last characters, which is why the
 * detail was removed wholesale rather than trimmed — that part is not coming
 * back.
 */
internal fun HttpException.pocketErrorMessage(): String? {
    // Retrofit buffers the error body for a failed call, so reading it here is
    // safe and does not consume a live stream.
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val explanation = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body

    return explanation
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
        ?.take(70)
}

/**
 * A rejected PUT to the pre-signed URL.
 *
 * S3 answers in XML, and the useful part is the Code element —
 * SignatureDoesNotMatch, RequestTimeTooSkewed, EntityTooLarge and so on. The
 * raw body is a wall of XML that truncates to nothing readable on a watch, so
 * the code is pulled out and the rest discarded.
 */
class S3UploadException(val code: Int, val errorCode: String?) : IOException(
    if (errorCode != null) "S3 $code: $errorCode" else "S3 upload failed (HTTP $code)"
)

/** Pulls the error code out of an S3 XML error body. */
internal fun s3ErrorCode(body: String): String? =
    Regex("<Code>([^<]{1,60})</Code>").find(body)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Thrown when no usable API key was baked into the build.
 *
 * Worth its own type because it is a build configuration mistake, not a
 * transport failure: retrying cannot help, and the fix is in local.properties
 * rather than anything the user can do on the watch.
 */
class MissingApiKeyException :
    IOException("No Pocket API key — set one on the watch, or in local.properties")

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
            // Recording length is uncapped, so a single PUT can legitimately
            // run for a long time on a slow link. UploadWorker promotes itself
            // to a foreground service so this can outlast WorkManager's own
            // execution budget.
            .writeTimeout(45, TimeUnit.MINUTES)
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
            @Suppress("UNCHECKED_CAST")
            val loader = json.serializersModule.serializer(type) as KSerializer<Any?>
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
            @Suppress("UNCHECKED_CAST")
            val saver = json.serializersModule.serializer(type) as KSerializer<Any?>
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
            // Read per request: the key can be entered or changed on the
            // watch while the process is alive.
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer ${PocketCredentials.current}")
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
        title: String? = null,
        durationSeconds: Long? = null,
        recordedAt: String? = null,
        contentType: String? = null
    ): ProvisionedUpload {
        if (!isApiKeyConfigured) throw MissingApiKeyException()

        return api.createUploadUrl(
            UploadUrlRequest(
                fileName = fileName,
                title = title,
                duration = durationSeconds,
                recordingAt = recordedAt,
                contentType = contentType
            )
        ).toProvisionedUpload()
    }

    // -----------------------------------------------------------------------
    // Step 3: S3 PUT
    // -----------------------------------------------------------------------

    /**
     * Streams [file] to the pre-signed [uploadUrl] with a raw OkHttp PUT.
     *
     * [contentType] must be byte-identical to the content_type sent during
     * provisioning — it can be part of the signature, and a mismatch surfaces
     * as a 403 SignatureDoesNotMatch rather than anything more descriptive.
     * Both now come from one constant, which is what makes that claim true;
     * it predated the field actually being sent.
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
                        val body = runCatching { res.body?.string().orEmpty() }
                            .getOrDefault("")
                        continuation.resumeWithException(
                            S3UploadException(res.code, s3ErrorCode(body))
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
                    401, 403 -> throw PocketPipelineException(
                        "Authorization rejected (HTTP ${http.code()})"
                    )

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
