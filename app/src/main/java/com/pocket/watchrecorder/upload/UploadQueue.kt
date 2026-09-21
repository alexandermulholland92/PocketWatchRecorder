package com.pocket.watchrecorder.upload

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
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
    /**
     * How many times the bandwidth guard has passed this entry over without
     * attempting it. Persisted so the guard can't starve a recording forever,
     * and so the UI can say "waiting for a faster link" rather than showing a
     * retry counter that is not actually counting.
     */
    val deferrals: Int = 0,
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
     * Out of retries. Defined here rather than in the worker and the ViewModel
     * separately, so "given up on" means the same thing in both — the two
     * copies of this rule used to be able to drift apart.
     */
    val isDeadLettered: Boolean
        get() = lastError != null && attempts >= UploadQueue.MAX_ATTEMPTS

    /** Still wants the worker to do something about it. */
    val needsUpload: Boolean
        get() = !uploaded && !isDeadLettered

    /**
     * Held back by the bandwidth guard rather than by a failure. Distinct from
     * retrying: nothing is being attempted at all while this is true.
     */
    val awaitingFasterLink: Boolean
        get() = deferrals > 0 && !uploaded && !isDeadLettered

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
 *
 * Every method that touches the disk is `suspend` and hops to [Dispatchers.IO].
 * These used to be blocking calls made from `viewModelScope` — i.e. directory
 * listings, JSON parsing and whole-file copies on the main thread, several
 * times a second.
 */
class UploadQueue(private val context: Context) {

    companion object {
        private const val TAG = "UploadQueue"
        private const val DIR_NAME = "uploads"
        private const val SIDECAR_SUFFIX = ".json"

        /** Attempts before an entry is considered dead-lettered. */
        const val MAX_ATTEMPTS = 5

        /** Assume a pre-signed URL is dead a minute before it really is. */
        const val URL_SAFETY_MARGIN_MS = 60_000L

        /** Fallback lifetime when the API doesn't report expires_in. */
        const val DEFAULT_URL_LIFETIME_MS = 45 * 60 * 1_000L

        internal val OFFSET_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /** Mirrors how Pocket names an untitled recording, minus the UTC. */
        internal val TITLE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

        /** RFC3339 requires a zone: either "Z" or a numeric offset. */
        internal val RFC3339_ZONE = Regex("(Z|[+-]\\d{2}:\\d{2})$")

        /**
         * Bumped by every writer, process-wide.
         *
         * Readers observe this instead of re-listing the directory on a timer.
         * The worker runs in the app's process, so its writes land here too.
         */
        private val revision = MutableStateFlow(0L)

        val changes: StateFlow<Long> = revision.asStateFlow()

        /** Nudges observers without a write — used as a slow safety net. */
        fun invalidate() {
            revision.value++
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    // -----------------------------------------------------------------------
    // Observation
    // -----------------------------------------------------------------------

    /** Every entry, re-read whenever something writes. */
    fun snapshots(): Flow<List<QueuedUpload>> = changes.map { all() }

    /** One entry, re-read whenever something writes. Null once it is removed. */
    fun snapshots(id: String): Flow<QueuedUpload?> = changes.map { find(id) }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /**
     * Takes ownership of [audio], moving it into the queue directory.
     *
     * Returns the persisted entry. The audio is safe on disk before this
     * returns, so the caller can treat the recording as durable immediately.
     */
    suspend fun enqueue(audio: File, title: String, durationMs: Long): QueuedUpload =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val target = File(dir, "$id-${audio.name}")

            if (!audio.renameTo(target)) {
                // Different filesystem, or a locked source — fall back to a copy.
                audio.copyTo(target, overwrite = true)
                audio.delete()
            }

            val recordedAt = recordedAtStamp(durationMs)
            val entry = QueuedUpload(
                id = id,
                fileName = target.name,
                // The same title the watch shows in its own library, so the
                // two agree before Pocket has said anything.
                title = localTitleFor(recordedAt) ?: title,
                durationSeconds = (durationMs / 1000).coerceAtLeast(1L),
                recordedAt = recordedAt,
                queuedAtEpochMs = System.currentTimeMillis()
            )
            writeBlocking(entry)
            Log.i(TAG, "Queued ${entry.id} (${target.length()} bytes)")
            entry
        }

    suspend fun update(entry: QueuedUpload) = withContext(Dispatchers.IO) {
        writeBlocking(entry)
    }

    private fun writeBlocking(entry: QueuedUpload) {
        runCatching {
            File(dir, entry.id + SIDECAR_SUFFIX).writeText(json.encodeToString(entry))
        }.onFailure { Log.e(TAG, "Could not persist ${entry.id}", it) }
        invalidate()
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    suspend fun find(id: String): QueuedUpload? = withContext(Dispatchers.IO) {
        read(File(dir, id + SIDECAR_SUFFIX))
    }

    /** Every job on disk, oldest first. */
    suspend fun all(): List<QueuedUpload> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.filter { it.name.endsWith(SIDECAR_SUFFIX) }
            ?.mapNotNull(::read)
            ?.sortedBy { it.queuedAtEpochMs }
            ?: emptyList()
    }

    /**
     * Jobs whose bytes still need to reach S3 and that have retries left.
     *
     * Dead-lettered entries are excluded on purpose: the worker used to retry
     * them forever and bail out of the loop on their failure, so one exhausted
     * entry at the head of the queue stopped every later recording from
     * uploading at all.
     */
    suspend fun awaitingUpload(): List<QueuedUpload> = all().filter { it.needsUpload }

    suspend fun audioFile(entry: QueuedUpload): File? = withContext(Dispatchers.IO) {
        File(dir, entry.fileName).takeIf { it.exists() && it.length() > 0L }
    }

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

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        read(File(dir, id + SIDECAR_SUFFIX))?.let { entry -> File(dir, entry.fileName).delete() }
        File(dir, id + SIDECAR_SUFFIX).delete()
        invalidate()
    }
}

/**
 * Start time of a recording that just ended, as RFC3339.
 *
 * This used to be a compile-time toggle between three guesses at what Pocket
 * wanted. The server settled it:
 *
 *     HTTP 400: invalid recording_at: must be RFC3339 format
 *               (e.g., 2006-01-02T15:04:05Z07:00)
 *
 * which rules out the wall-clock-with-no-zone form that was being sent. Of the
 * two that remain, a real offset is the only one that is also *true*: labelling
 * local time as UTC parses fine and stores an instant hours away from when the
 * recording happened. That mistake is visible in this account's own history,
 * where some entries carry a recording_at seven hours off their created_at.
 *
 * An explicit formatter rather than toString(): the latter drops ":00" seconds,
 * and a strict parser is exactly what we are dealing with.
 */
internal fun recordedAtStamp(
    durationMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    now: LocalDateTime = LocalDateTime.now(zone)
): String = now
    .minus(Duration.ofMillis(durationMs))
    .truncatedTo(ChronoUnit.SECONDS)
    .atZone(zone)
    .format(UploadQueue.OFFSET_FORMAT)

/**
 * The recording's title, in its own local time.
 *
 * Sent at provisioning and used for the watch's library list. Pocket's own
 * default renders the timestamp in UTC, and it will not rename a recording
 * afterwards — PATCH on the recording endpoint answers 405 with "allow: GET"
 * — so supplying it up front is the only way the title can show the right
 * time. The cost is Pocket's naming from the transcript, which a supplied
 * title suppresses; a title that reads correctly is worth more.
 *
 * Derived from [recordedAt] rather than the clock, so it stays right for an
 * entry that sat in the queue overnight. The offset carried in the timestamp
 * is the one the recording was made in, which is what should be displayed.
 */
internal fun localTitleFor(recordedAt: String): String? = runCatching {
    "Recording " + OffsetDateTime.parse(recordedAt.trim()).format(UploadQueue.TITLE_FORMAT)
}.getOrNull()

/**
 * Brings a stored timestamp up to RFC3339.
 *
 * Recordings queued by an earlier build hold a zone-less string and would be
 * rejected forever, which is the worst kind of bug: audio captured, saved, and
 * permanently unsendable. Those were written as local wall clock, so attaching
 * the device's offset recovers the instant they meant.
 *
 * Anything already carrying a zone is left exactly as it is.
 */
internal fun normalizeRecordedAt(
    stored: String,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val trimmed = stored.trim()
    if (trimmed.isEmpty()) return trimmed
    if (UploadQueue.RFC3339_ZONE.containsMatchIn(trimmed)) return trimmed

    return runCatching {
        LocalDateTime.parse(trimmed).atZone(zone).format(UploadQueue.OFFSET_FORMAT)
    }.getOrDefault(trimmed)
}
