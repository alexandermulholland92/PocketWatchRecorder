package com.pocket.watchrecorder.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocket.watchrecorder.R
import com.pocket.watchrecorder.audio.AudioRecorderManager
import com.pocket.watchrecorder.network.MissingApiKeyException
import com.pocket.watchrecorder.network.PocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.IOException
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

        private const val CHANNEL_ID = "uploads"
        private const val NOTIFICATION_ID = 1002

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

        /**
         * Runs the queue now, cancelling any pending backoff.
         *
         * Only for an explicit "retry" from the user: after a failure the work
         * sits in exponential backoff for up to four minutes, and KEEP would
         * make their tap do nothing visible. REPLACE can interrupt an upload
         * already in flight, but a stop mid-PUT costs that entry no attempts
         * and the worker drains the whole queue again on the next pass.
         */
        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private val queue = UploadQueue(applicationContext)

    override suspend fun doWork(): Result {
        val pending = queue.awaitingUpload()
        if (pending.isEmpty()) return Result.success()

        // Promote to a foreground service. Without this the worker is capped at
        // roughly ten minutes, which a large recording on the Bluetooth proxy
        // can exceed — it would be stopped mid-PUT and restart from zero, every
        // time, forever.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "Continuing without foreground promotion", it) }

        var needsAnotherPass = false

        for (entry in pending) {
            if (isStopped) return Result.retry()

            val audio = queue.audioFile(entry)
            if (audio == null) {
                Log.w(TAG, "Audio missing for ${entry.id}; dropping the job")
                queue.remove(entry.id)
                continue
            }

            if (!linkCanCarry(entry, audio.length())) {
                Log.i(TAG, "Deferring ${entry.id}: link too slow for ${audio.length()} bytes")
                // Recorded, so the guard can't pass the same entry over
                // forever and so the UI can distinguish this from a retry.
                queue.update(entry.copy(deferrals = entry.deferrals + 1))
                needsAnotherPass = true
                continue
            }

            try {
                var current = entry

                // --- Provision, but only if we don't already hold a live URL --
                if (!current.hasUsableUrl) {
                    val provision = PocketClient.createUpload(
                        // removePrefix, not substringAfter('-'): the id is a
                        // UUID and contains dashes of its own, so the old form
                        // sent Pocket a truncated name like
                        // "4f89-11d3-...-watch_20260911.m4a".
                        fileName = current.fileName.removePrefix("${current.id}-"),
                        // No title: Pocket names it from the transcript.
                        durationSeconds = current.durationSeconds,
                        recordedAt = current.recordedAt
                    )
                    val lifetime = provision.expiresInSeconds?.times(1_000L)
                        ?: UploadQueue.DEFAULT_URL_LIFETIME_MS

                    current = current.copy(
                        recordingId = provision.recordingId,
                        uploadUrl = provision.uploadUrl,
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
                    // Noticed on the very next chunk rather than only between
                    // entries, so a stop doesn't keep streaming bytes.
                    if (isStopped) throw IOException("Worker stopped")

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

                queue.update(current.copy(uploaded = true, lastError = null, deferrals = 0))
                UploadProgress.clear(current.id)
                audio.delete()
                Log.i(TAG, "Uploaded ${entry.id} -> ${current.recordingId}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                UploadProgress.clear(entry.id)

                if (isStopped) {
                    // We were stopped mid-transfer. Not this entry's fault, so
                    // don't spend one of its attempts on it.
                    return Result.retry()
                }

                // A permanent failure is spent immediately rather than
                // burned down over five attempts and four minutes of backoff:
                // the user just sees a stalled counter while nothing changes.
                val permanent = t.isPermanent()
                val attempts =
                    if (permanent) UploadQueue.MAX_ATTEMPTS else entry.attempts + 1

                queue.update(
                    entry.copy(
                        attempts = attempts,
                        lastError = t.shortMessage(),
                        deferrals = 0
                    )
                )
                Log.e(TAG, "Upload failed for ${entry.id} (attempt $attempts)", t)

                // Move on to the next entry rather than abandoning the run.
                // Returning here meant one exhausted entry at the head of the
                // queue blocked every later recording indefinitely.
                if (attempts < UploadQueue.MAX_ATTEMPTS && !permanent) {
                    needsAnotherPass = true
                }
            }
        }

        // Anything added while we were running, or deferred above, gets another pass.
        return if (needsAnotherPass || queue.awaitingUpload().isNotEmpty()) {
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
    private fun linkCanCarry(entry: QueuedUpload, bytes: Long): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }

        return shouldAttemptUpload(
            attempts = entry.attempts,
            deferrals = entry.deferrals,
            bytes = bytes,
            isWifi = capabilities
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            // No capabilities at all reads the same as no estimate: unknown,
            // so attempt it rather than defer on a guess.
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: -1
        )
    }

    /** A missing key or a 4xx will not fix itself; everything else might. */
    private fun Throwable.isPermanent(): Boolean = when (this) {
        is MissingApiKeyException -> true
        is HttpException -> code() in 400..499 && code() !in setOf(408, 425, 429)
        else -> false
    }

    private fun Throwable.shortMessage(): String = when (this) {
        is MissingApiKeyException -> "No API key"
        is HttpException -> "HTTP ${code()}"
        else -> this::class.java.simpleName + (message?.let { ": ${it.take(50)}" } ?: "")
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.upload_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = applicationContext.getString(R.string.upload_channel_description)
            setShowBadge(false)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}

/**
 * Above this size, an upload is not worth starting on a very slow link.
 *
 * On Wear, "connected" is often the Bluetooth proxy to the phone, which runs at
 * a few KB/s. A minute of audio is roughly 480 KB, so a naive upload there
 * stalls for many minutes and usually times out. There is no NetworkType that
 * means "not Bluetooth", so the reported upstream bandwidth is the only signal
 * available.
 */
internal const val LARGE_FILE_BYTES = 512L * 1024

internal const val MIN_UPSTREAM_KBPS_FOR_LARGE = 400

/**
 * Hard cap on how often the bandwidth guard may pass an entry over before it
 * is attempted regardless.
 */
internal const val MAX_LINK_DEFERRALS = 3

/**
 * Whether to attempt [bytes] now, or hold out for a better link.
 *
 * File-scope and pure because the version of this that lived inside the worker
 * could starve a recording indefinitely, and that is not something a comment
 * can be trusted to prevent. Two of its answers were wrong:
 *
 *  - an unknown upstream estimate (0, which is what the Wear Bluetooth proxy
 *    usually reports) counted as "too slow";
 *  - an entry that had already been attempted could still be deferred.
 *
 * Either way the entry's attempt count stopped moving, so it never failed and
 * never succeeded — the UI just showed a retry counter frozen at 1.
 */
internal fun shouldAttemptUpload(
    attempts: Int,
    deferrals: Int,
    bytes: Long,
    isWifi: Boolean,
    upstreamKbps: Int
): Boolean = when {
    bytes < LARGE_FILE_BYTES -> true
    attempts > 0 -> true                  // already committed to this one
    deferrals >= MAX_LINK_DEFERRALS -> true
    isWifi -> true
    upstreamKbps <= 0 -> true              // no estimate is not evidence of a slow link
    else -> upstreamKbps >= MIN_UPSTREAM_KBPS_FOR_LARGE
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
