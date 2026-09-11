package com.pocket.watchrecorder.upload

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
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
            recordedAt = Instant.now().minusMillis(durationMs).toString(),
            queuedAtEpochMs = System.currentTimeMillis()
        )
        write(entry)
        Log.i(TAG, "Queued ${entry.id} (${target.length()} bytes)")
        return entry
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

    /** Jobs still needing an upload or a summary. */
    fun unfinished(): List<QueuedUpload> = all().filterNot { it.isComplete }

    fun oldest(): QueuedUpload? = all().firstOrNull()

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

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}