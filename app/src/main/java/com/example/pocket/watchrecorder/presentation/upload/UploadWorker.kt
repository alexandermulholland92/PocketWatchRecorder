package com.pocket.watchrecorder.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocket.watchrecorder.audio.AudioRecorderManager
import com.pocket.watchrecorder.network.PocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/**
 * Drains [UploadQueue] whenever the network allows.
 *
 * WorkManager is used rather than an in-process ConnectivityManager callback
 * for one specific reason: it can wake an app the system has frozen. Samsung's
 * MARs freezer suspends Wear apps aggressively, and an in-process listener is
 * suspended right along with everything else. WorkManager also gives us
 * backoff, persistence across reboot, and Doze handling for free.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_NAME = "pocket-upload-queue"
        const val KEY_PROGRESS = "progress_percent"

        /**
         * Above this size, refuse to start on a very slow link.
         *
         * On Wear, "connected" is often the Bluetooth proxy to the phone, which
         * runs at a few KB/s. A minute of audio is roughly 480 KB, so a naive
         * upload there stalls for many minutes and usually times out. There is
         * no NetworkType that means "not Bluetooth", so we inspect the reported
         * upstream bandwidth instead.
         */
        private const val LARGE_FILE_BYTES = 512L * 1024
        private const val MIN_UPSTREAM_KBPS_FOR_LARGE = 400

        private const val MAX_ATTEMPTS = 5

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // KEEP, not REPLACE: replacing would cancel an upload already in
            // flight. The worker drains the whole queue, and re-queues itself
            // if anything is still pending when it finishes.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val queue = UploadQueue(applicationContext)
        val pending = queue.awaitingUpload()

        if (pending.isEmpty()) return Result.success()

        var deferredForBandwidth = false

        for (entry in pending) {
            if (isStopped) return Result.retry()

            val audio = queue.audioFile(entry)
            if (audio == null) {
                Log.w(TAG, "Audio missing for ${entry.id}; dropping the job")
                queue.remove(entry.id)
                continue
            }

            if (!linkCanCarry(audio.length())) {
                Log.i(TAG, "Deferring ${entry.id}: link too slow for ${audio.length()} bytes")
                deferredForBandwidth = true
                continue
            }

            try {
                var current = entry

                // --- Provision, but only if we don't already hold a live URL --
                if (!current.hasUsableUrl) {
                    val provision = PocketClient.createUpload(
                        fileName = current.fileName.substringAfter('-'),
                        title = current.title,
                        durationSeconds = current.durationSeconds,
                        recordedAt = current.recordedAt
                    )
                    val lifetime = (provision.expiresIn?.times(1_000L))
                        ?: UploadQueue.DEFAULT_URL_LIFETIME_MS

                    current = current.copy(
                        recordingId = provision.resolvedRecordingId,
                        uploadUrl = provision.resolvedUploadUrl,
                        urlExpiresAtEpochMs = System.currentTimeMillis() +
                                lifetime - UploadQueue.URL_SAFETY_MARGIN_MS
                    )
                    // Persist BEFORE the PUT. This is the line that prevents
                    // duplicate recordings if we die mid-transfer.
                    queue.update(current)
                }

                val totalBytes = audio.length()
                var lastPersistedBucket = -1

                PocketClient.uploadRecording(
                    uploadUrl = current.uploadUrl!!,
                    file = audio,
                    contentType = AudioRecorderManager.CONTENT_TYPE
                ) { fraction ->
                    // Every tick goes to the UI...
                    UploadProgress.report(current.id, fraction, totalBytes)

                    // ...but only every 5% gets written to WorkManager's store,
                    // which is only needed if the UI process restarts mid-upload.
                    val bucket = (fraction * 20).toInt()
                    if (bucket != lastPersistedBucket) {
                        lastPersistedBucket = bucket
                        setProgressAsync(workDataOf(KEY_PROGRESS to (fraction * 100).toInt()))
                    }
                }

                queue.update(current.copy(uploaded = true, lastError = null))
                UploadProgress.clear(current.id)
                audio.delete()
                Log.i(TAG, "Uploaded ${entry.id} -> ${current.recordingId}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                UploadProgress.clear(entry.id)
                val attempts = entry.attempts + 1
                val message = t.shortMessage()
                queue.update(entry.copy(attempts = attempts, lastError = message))
                Log.e(TAG, "Upload failed for ${entry.id} (attempt $attempts)", t)

                if (attempts >= MAX_ATTEMPTS || t.isPermanent()) return Result.failure()
                return Result.retry()
            }
        }

        // Anything added while we were running, or deferred above, gets another pass.
        return if (deferredForBandwidth || queue.awaitingUpload().isNotEmpty()) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    /**
     * Whether the active link is plausibly fast enough for [bytes].
     *
     * The reported bandwidth is an estimate the platform doesn't measure
     * precisely, so this is a guard against the obviously-hopeless case rather
     * than a real scheduler.
     */
    private fun linkCanCarry(bytes: Long): Boolean {
        if (bytes < LARGE_FILE_BYTES) return true

        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return true
        val capabilities = manager.activeNetwork
            ?.let { manager.getNetworkCapabilities(it) }
            ?: return false

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        return capabilities.linkUpstreamBandwidthKbps >= MIN_UPSTREAM_KBPS_FOR_LARGE
    }

    private fun Throwable.isPermanent(): Boolean =
        this is HttpException && code() in 400..499 && code() !in setOf(408, 425, 429)

    private fun Throwable.shortMessage(): String = when (this) {
        is HttpException -> "HTTP ${code()}"
        else -> this::class.java.simpleName + (message?.let { ": ${it.take(50)}" } ?: "")
    }
}

/**
 * Live upload progress, published straight from the worker's byte stream.
 *
 * WorkManager's own setProgress round-trips through its database, which is both
 * throttled and laggy — fine as a durable fallback, too coarse for a progress
 * ring. Workers run in the app's process by default, so a plain StateFlow gets
 * the same numbers to the UI with no latency and no disk writes.
 *
 * If the process was started by WorkManager with no UI and later dies, this
 * resets to null and the UI falls back to the persisted WorkInfo value.
 */
object UploadProgress {

    data class Snapshot(val entryId: String, val fraction: Float, val totalBytes: Long)

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    fun report(entryId: String, fraction: Float, totalBytes: Long) {
        _state.value = Snapshot(entryId, fraction.coerceIn(0f, 1f), totalBytes)
    }

    fun fractionFor(entryId: String): Float? =
        _state.value?.takeIf { it.entryId == entryId }?.fraction

    fun clear(entryId: String) {
        if (_state.value?.entryId == entryId) _state.value = null
    }
}

/** Convenience for reading the progress value back out of a WorkInfo. */
fun Data.uploadProgressFraction(): Float? =
    getInt(UploadWorker.KEY_PROGRESS, -1).takeIf { it >= 0 }?.div(100f)