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
 * The Pocket key, supplied at build time from `local.properties`.
 *
 * There is deliberately no in-source fallback constant: the one that used to
 * live here invited pasting a live key into a tracked file, which is exactly
 * how this repository leaked one. Put the key in `local.properties`
 * (see `local.properties.example`) and nowhere else.
 *
 * Note this still ships in the APK as a plaintext constant — fine for a
 * personal sideload, not fine for a build you hand to someone else.
 */
internal val API_KEY: String = BuildConfig.POCKET_API_KEY
    .removePrefix("Bearer ")
    .trim()

internal val isApiKeyConfigured: Boolean
    get() = API_KEY.isNotBlank()

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
    @SerialName("recording_at") val recordingAt: String? = null
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

/** Thrown when no API key was baked into the build. */
class MissingApiKeyException : IOException("No Pocket API key in this build")

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
        title: String? = null,
        durationSeconds: Long? = null,
        recordedAt: String? = null
    ): ProvisionedUpload {
        if (!isApiKeyConfigured) throw MissingApiKeyException()

        return api.createUploadUrl(
            UploadUrlRequest(
                fileName = fileName,
                title = title,
                duration = durationSeconds,
                recordingAt = recordedAt
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
