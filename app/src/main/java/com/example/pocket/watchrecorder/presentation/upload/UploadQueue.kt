package com.pocket.watchrecorder.upload

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One queued recording, persisted as a JSON sidecar next to its audio file.
 *
 * The reason this exists on disk rather than in the ViewModel: provisioning is
 * NOT idempotent. Every call to the upload-url endpoint mints a new recording
 * in Pocket. If the process dies between provisioning and a successful PUT and
 * we only kept that id in memory, each retry would leave another orphaned
 * recording behind. Writing [recordingId] down before the PUT starts is what
 * makes a retry resume rather than duplicate.
 */
/** How `recording_at` is rendered on the wire. See [UploadQueue.recordedAtStamp]. */
private enum class RecordedAtFormat {
    /** "2026-09-11T15:28:00" — no zone marker. */
    LOCAL_NAIVE,

    /** "2026-09-11T15:28:00Z" — wall clock mislabelled as UTC. */
    LOCAL_AS_UTC,

    /** "2026-09-11T15:28:00-07:00" — genuinely correct instant. */
    OFFSET
}

@Serializable
data class QueuedUpload(
    val id: String,
    val fileName: String,
    val title: String,
    val durationSeconds: Long,
    val recordedAt: String,
    val recordingId: String? = null,
    val uploadUrl: String? = null,
    val urlExpiresAtEpochMs: Long = 0L,
    val uploaded: Boolean = false,
    val attempts: Int = 0,
    val lastError: String? = null,
    val queuedAtEpochMs: Long = 0L,
    /**
     * Written once the summary arrives, so a finished recording survives the
     * app being closed and can be re-read from the library later.
     */
    val summary: String? = null,
    val summaryTitle: String? = null,
    val completedAtEpochMs: Long = 0L
) {
    val isComplete: Boolean
        get() = !summary.isNullOrBlank()

    /**
     * Pre-signed URLs expire. Inside the window a retry can re-PUT to the same
     * key, which is free and idempotent. Outside it we have to re-provision and
     * accept an orphaned pending recording — unavoidable, so the window is
     * treated conservatively (see [UploadQueue.URL_SAFETY_MARGIN_MS]).
     */
    val hasUsableUrl: Boolean
        get() = !uploadUrl.isNullOrBlank() &&
                !recordingId.isNullOrBlank() &&
                System.currentTimeMillis() < urlExpiresAtEpochMs
}

/**
 * A tiny durable queue backed by the filesystem.
 *
 * Deliberately kept in its own directory rather than sharing the recorder's, so
 * [com.pocket.watchrecorder.audio.AudioRecorderManager.purgeAll] can't delete a
 * job that hasn't been uploaded yet.
 */
class UploadQueue(private val context: Context) {

    companion object {
        private const val TAG = "UploadQueue"
        private const val DIR_NAME = "uploads"
        private const val SIDECAR_SUFFIX = ".json"

        /** Assume a pre-signed URL is dead a minute before it really is. */
        const val URL_SAFETY_MARGIN_MS = 60_000L

        /** Fallback lifetime when the API doesn't report expires_in. */
        const val DEFAULT_URL_LIFETIME_MS = 45 * 60 * 1_000L

        /** See [recordedAtStamp]. */
        private val RECORDED_AT_FORMAT = RecordedAtFormat.LOCAL_NAIVE

        private val LOCAL_NAIVE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val OFFSET_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /**
     * Takes ownership of [audio], moving it into the queue directory.
     *
     * Returns the persisted entry. The audio is safe on disk before this
     * returns, so the caller can treat the recording as durable immediately.
     */
    fun enqueue(audio: File, title: String, durationMs: Long): QueuedUpload {
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id-${audio.name}")

        if (!audio.renameTo(target)) {
            // Different filesystem, or a locked source — fall back to a copy.
            audio.copyTo(target, overwrite = true)
            audio.delete()
        }

        val entry = QueuedUpload(
            id = id,
            fileName = target.name,
            title = title,
            durationSeconds = (durationMs / 1000).coerceAtLeast(1L),
            recordedAt = recordedAtStamp(durationMs),
            queuedAtEpochMs = System.currentTimeMillis()
        )
        write(entry)
        Log.i(TAG, "Queued ${entry.id} (${target.length()} bytes)")
        return entry
    }

    /**
     * Start time of a recording that just ended.
     *
     * Pocket displays `recording_at` without converting to local time, but it
     * does store whatever instant the string resolves to. That makes the two
     * obvious formats mutually exclusive:
     *
     *  - OFFSET      "…15:28:00-07:00" — instant correct, title reads 7h late
     *  - LOCAL_AS_UTC "…15:28:00Z"     — title correct, instant 7h early
     *
     * LOCAL_NAIVE sends no zone marker at all, on the theory that Pocket takes
     * an unqualified datetime at face value rather than shifting it. If that
     * holds, both the title and the stored value are right.
     */
    private fun recordedAtStamp(durationMs: Long): String {
        val startedAt = LocalDateTime.now(ZoneId.systemDefault())
            .minus(Duration.ofMillis(durationMs))
            .truncatedTo(ChronoUnit.SECONDS)

        return when (RECORDED_AT_FORMAT) {
            // Explicit formatters: LocalDateTime.toString() drops ":00" seconds,
            // which a strict server-side parser may reject.
            RecordedAtFormat.LOCAL_NAIVE -> startedAt.format(LOCAL_NAIVE_FORMAT)
            RecordedAtFormat.LOCAL_AS_UTC -> startedAt.format(LOCAL_NAIVE_FORMAT) + "Z"
            RecordedAtFormat.OFFSET ->
                startedAt.atZone(ZoneId.systemDefault()).format(OFFSET_FORMAT)
        }
    }

    fun update(entry: QueuedUpload) = write(entry)

    private fun write(entry: QueuedUpload) {
        runCatching {
            File(dir, entry.id + SIDECAR_SUFFIX).writeText(json.encodeToString(entry))
        }.onFailure { Log.e(TAG, "Could not persist ${entry.id}", it) }
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    fun find(id: String): QueuedUpload? = read(File(dir, id + SIDECAR_SUFFIX))

    /** Every job still needing an upload or a summary, oldest first. */
    fun all(): List<QueuedUpload> =
        dir.listFiles()
            ?.filter { it.name.endsWith(SIDECAR_SUFFIX) }
            ?.mapNotNull(::read)
            ?.sortedBy { it.queuedAtEpochMs }
            ?: emptyList()

    /** Jobs whose bytes still need to reach S3. */
    fun awaitingUpload(): List<QueuedUpload> = all().filterNot { it.uploaded }



    fun audioFile(entry: QueuedUpload): File? =
        File(dir, entry.fileName).takeIf { it.exists() && it.length() > 0L }

    private fun read(file: File): QueuedUpload? =
        if (!file.exists()) null
        else runCatching { json.decodeFromString<QueuedUpload>(file.readText()) }
            .onFailure {
                Log.w(TAG, "Corrupt sidecar ${file.name}; dropping", it)
                file.delete()
            }
            .getOrNull()

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    fun remove(id: String) {
        find(id)?.let { entry -> File(dir, entry.fileName).delete() }
        File(dir, id + SIDECAR_SUFFIX).delete()
    }

}