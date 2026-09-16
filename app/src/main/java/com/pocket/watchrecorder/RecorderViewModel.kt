package com.pocket.watchrecorder

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pocket.watchrecorder.audio.RecordingBus
import com.pocket.watchrecorder.audio.RecordingService
import com.pocket.watchrecorder.network.MissingApiKeyException
import com.pocket.watchrecorder.network.PocketClient
import com.pocket.watchrecorder.network.PocketPipelineException
import com.pocket.watchrecorder.upload.QueuedUpload
import com.pocket.watchrecorder.upload.UploadProgress
import com.pocket.watchrecorder.upload.UploadQueue
import com.pocket.watchrecorder.upload.UploadWorker
import com.pocket.watchrecorder.upload.uploadProgressFraction
import com.pocket.watchrecorder.ui.relativeAge
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

// ===========================================================================
// State
// ===========================================================================

enum class Phase(val label: String) {
    // Provisioning lives inside UploadWorker now and is over in well under a
    // second, so it no longer gets its own UI phase.
    UPLOADING("Uploading"),
    PROCESSING("Summarizing")
}

/** What the main screen is showing for the recording currently in focus. */
sealed interface UiState {
    data object Idle : UiState

    data class Recording(val elapsedMs: Long, val level: Float) : UiState

    data class Working(
        val phase: Phase,
        val progress: Float? = null,
        val detail: String? = null
    ) : UiState

    data class Done(val title: String?, val summary: String) : UiState

    data class Failed(val message: String, val canRetry: Boolean) : UiState
}

enum class ItemStatus(val label: String) {
    WAITING("Waiting"),
    UPLOADING("Uploading"),
    PROCESSING("Summarizing"),
    READY("Ready"),
    FAILED("Failed")
}

/** One row in the library list. */
data class QueueItem(
    val id: String,
    val title: String,
    val ageLabel: String,
    val status: ItemStatus,
    val progress: Float?,
    val summary: String?
)

sealed interface Route {
    data object Main : Route
    data object Library : Route
    data class Detail(val id: String) : Route
}

// ===========================================================================
// ViewModel
// ===========================================================================

/**
 * Follows every queued recording concurrently.
 *
 * Two things this deliberately does NOT own:
 *
 *  - the microphone, which belongs to [RecordingService], so capture survives
 *    the Activity going away;
 *  - the transfer, which belongs to [UploadWorker].
 *
 * What is left is tracking: each queue entry gets its own coroutine, keyed by
 * id. Starting a new recording doesn't cancel the one before it — that earlier
 * recording keeps uploading, keeps polling, and writes its summary into its own
 * sidecar when it lands. The main screen follows whichever entry is "focused";
 * the rest surface in the library.
 *
 * Nothing here polls the disk on a timer any more. [UploadQueue.changes] emits
 * when a writer — this process's UI or its worker — actually changes something.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "RecorderViewModel"
        const val WAKE_LOCK_TAG = "PocketWatch::pipeline"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L

        /**
         * Safety net only. Every writer invalidates the queue directly, so this
         * exists purely so a signal lost across a process boundary can't strand
         * the UI. It replaces what used to be a 1-4 second disk poll.
         */
        const val QUEUE_HEARTBEAT_MS = 30_000L
    }

    private val queue = UploadQueue(application)
    private val powerManager =
        application.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _library = MutableStateFlow<List<QueueItem>>(emptyList())
    val library: StateFlow<List<QueueItem>> = _library.asStateFlow()

    private val _route = MutableStateFlow<Route>(Route.Main)
    val route: StateFlow<Route> = _route.asStateFlow()

    /** One tracker per queue entry, keyed by entry id. */
    private val trackers = mutableMapOf<String, Job>()

    /** Which entry the main screen reflects. Null means "nothing in focus". */
    private var focusedId: String? = null

    private var workState: WorkInfo.State? = null
    private var workProgress: Float? = null

    init {
        // Live capture state, straight from the recording service.
        viewModelScope.launch {
            RecordingBus.mic.collect { mic ->
                if (mic.active) _state.value = UiState.Recording(mic.elapsedMs, mic.level)
            }
        }

        // What a finished take turned into.
        viewModelScope.launch {
            RecordingBus.outcomes.collect { outcome ->
                when (outcome) {
                    is RecordingBus.Outcome.Queued -> {
                        focusedId = outcome.entryId
                        _route.value = Route.Main
                        ensureTracked(outcome.entryId)
                    }

                    is RecordingBus.Outcome.Failed -> {
                        focusedId = null
                        _state.value = UiState.Failed(outcome.message, canRetry = false)
                    }
                }
            }
        }

        viewModelScope.launch {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(UploadWorker.WORK_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    val stateChanged = info?.state != workState
                    workState = info?.state
                    workProgress = info?.progress?.uploadProgressFraction()
                    // Lets any tracker redraw its "waiting for network" line.
                    if (stateChanged) UploadQueue.invalidate()
                }
        }

        // Live upload progress, applied to the focused entry only.
        viewModelScope.launch {
            UploadProgress.state.collect { snapshot ->
                if (snapshot == null || snapshot.entryId != focusedId) return@collect
                val current = _state.value
                if (current is UiState.Working && current.phase == Phase.UPLOADING) {
                    _state.value = current.copy(progress = snapshot.fraction)
                }
            }
        }

        // Single supervisor: keeps the library in step with the disk and
        // guarantees that every entry has a tracker — including ones left over
        // from a previous launch. Re-runs when the queue changes, and when
        // upload progress ticks (which changes a row's label but not the disk).
        viewModelScope.launch {
            combine(queue.snapshots(), UploadProgress.state) { entries, _ -> entries }
                .collect { entries ->
                    _library.value = entries.map { it.toItem() }
                    entries.forEach { if (!it.isComplete) ensureTracked(it.id) }
                }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(QUEUE_HEARTBEAT_MS)
                UploadQueue.invalidate()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public intents
    // -----------------------------------------------------------------------

    fun onPrimaryAction() {
        val app = getApplication<Application>()
        if (RecordingBus.mic.value.active) {
            RecordingService.stop(app)
        } else {
            // Focus moves to the new take; earlier entries keep their trackers.
            focusedId = null
            _route.value = Route.Main
            // Optimistic: the service confirms within a frame or two, and
            // replaces this with a Failed state if the mic won't open.
            _state.value = UiState.Recording(0L, 0f)
            RecordingService.start(app)
        }
    }

    fun openLibrary() {
        _route.value = Route.Library
    }

    fun openDetail(id: String) {
        _route.value = Route.Detail(id)
    }

    fun backToMain() {
        _route.value = Route.Main
    }

    /** Clears the main screen without touching anything still in flight. */
    fun dismissFocused() {
        val id = focusedId
        focusedId = null
        _state.value = UiState.Idle
        _route.value = Route.Main

        if (id == null) return
        viewModelScope.launch {
            val entry = queue.find(id)
            // Only reclaim space for something the user has actually seen.
            if (entry != null && (entry.isComplete || entry.isDeadLettered)) {
                trackers.remove(id)?.cancel()
                queue.remove(id)
            }
        }
    }

    /** Removes one recording and stops following it. */
    fun discard(id: String) {
        trackers.remove(id)?.cancel()
        if (focusedId == id) {
            focusedId = null
            _state.value = UiState.Idle
        }
        viewModelScope.launch { queue.remove(id) }
    }

    /**
     * Removes every finished recording, leaving anything still uploading or
     * summarizing alone. Scoped this way on purpose — the old blanket clear()
     * could delete audio that had never reached S3.
     */
    fun clearFinished() {
        viewModelScope.launch {
            queue.all()
                .filter { it.isComplete }
                .forEach { entry ->
                    trackers.remove(entry.id)?.cancel()
                    queue.remove(entry.id)
                    if (focusedId == entry.id) {
                        focusedId = null
                        _state.value = UiState.Idle
                    }
                }
        }
    }

    fun retryFocused() {
        val id = focusedId
        if (id == null) {
            dismissFocused()
            return
        }
        viewModelScope.launch {
            val entry = queue.find(id)
            if (entry == null) {
                dismissFocused()
                return@launch
            }
            queue.update(entry.copy(attempts = 0, lastError = null))
            UploadWorker.schedule(getApplication())
            ensureTracked(id)
        }
    }

    // -----------------------------------------------------------------------
    // Per-entry tracking
    // -----------------------------------------------------------------------

    private fun ensureTracked(entryId: String) {
        trackers[entryId]?.let { if (it.isActive) return }
        trackers[entryId] = viewModelScope.launch {
            try {
                track(entryId)
            } finally {
                trackers.remove(entryId)
            }
        }
    }

    /**
     * Follows one entry from "queued" to "summarized".
     *
     * Owns none of the transfer — [UploadWorker] does the provisioning and the
     * PUT. If this coroutine dies, the upload still completes and the supervisor
     * starts a fresh tracker from the sidecar.
     */
    private suspend fun track(entryId: String) {
        val initial = queue.find(entryId) ?: return
        if (initial.isComplete) {
            publish(entryId, UiState.Done(initial.summaryTitle, initial.summary.orEmpty()))
            return
        }

        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        try {
            // --- Wait for the worker to land the bytes in S3 -----------------
            val settled = queue.snapshots(entryId)
                .onEach { latest ->
                    if (latest != null && !latest.uploaded && !latest.isDeadLettered) {
                        publishUploading(entryId, latest)
                    }
                }
                .first { it == null || it.uploaded || it.isDeadLettered }
                ?: return   // removed while we were watching

            if (settled.isDeadLettered) {
                publish(
                    entryId,
                    UiState.Failed(settled.lastError ?: "Upload failed", canRetry = true)
                )
                return
            }

            val recordingId = settled.recordingId
            if (recordingId.isNullOrBlank()) {
                publish(entryId, UiState.Failed("Uploaded without an id", canRetry = true))
                return
            }

            // --- Poll for the summary ---------------------------------------
            publish(entryId, UiState.Working(Phase.PROCESSING))
            val recording = PocketClient.awaitProcessing(recordingId) { status ->
                publish(entryId, UiState.Working(Phase.PROCESSING, detail = status))
            }

            val text = recording.summaryText
                ?: recording.transcriptText
                ?: "Processed, but no summary text was returned."

            // Persist before publishing, so a restart can still read it.
            queue.find(entryId)?.let { latest ->
                queue.update(
                    latest.copy(
                        summary = text,
                        summaryTitle = recording.title,
                        completedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
            publish(entryId, UiState.Done(recording.title, text))
        } catch (timeout: TimeoutCancellationException) {
            // Must precede the CancellationException branch: it is one.
            Log.w(TAG, "Timed out waiting for the summary", timeout)
            publish(entryId, UiState.Failed("Still processing — check back", canRetry = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "Tracking failed for $entryId", t)
            publish(entryId, UiState.Failed(t.toUserMessage(), canRetry = true))
        } finally {
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
        }
    }

    private fun publishUploading(entryId: String, entry: QueuedUpload) {
        publish(
            entryId,
            UiState.Working(
                phase = Phase.UPLOADING,
                progress = UploadProgress.fractionFor(entryId) ?: workProgress,
                detail = when {
                    entry.lastError != null -> "retrying · ${entry.attempts}"
                    workState == WorkInfo.State.ENQUEUED -> "waiting for network"
                    else -> null
                }
            )
        )
    }

    /** Main-screen updates apply only to the entry the user is watching. */
    private fun publish(entryId: String, next: UiState) {
        if (entryId == focusedId) _state.value = next
    }

    private fun QueuedUpload.toItem(): QueueItem = QueueItem(
        id = id,
        title = summaryTitle?.takeIf { it.isNotBlank() } ?: title,
        ageLabel = relativeAge(queuedAtEpochMs),
        status = when {
            isComplete -> ItemStatus.READY
            isDeadLettered -> ItemStatus.FAILED
            uploaded -> ItemStatus.PROCESSING
            UploadProgress.fractionFor(id) != null -> ItemStatus.UPLOADING
            else -> ItemStatus.WAITING
        },
        progress = UploadProgress.fractionFor(id),
        summary = summary
    )

    /**
     * A short, non-technical explanation.
     *
     * This used to print the API key's first six and last four characters plus
     * its length on a 401, and to walk the cause chain and show the root
     * exception's class name for anything unrecognized. Both were debugging
     * aids that had no business on a user's wrist; the detail belongs in logcat,
     * which is where it now goes.
     */
    private fun Throwable.toUserMessage(): String = when (this) {
        is MissingApiKeyException -> "No API key in this build"
        is UnknownHostException -> "No connection"
        is SocketTimeoutException -> "Network timed out"
        is PocketPipelineException -> message ?: "Processing failed"
        is HttpException -> when (code()) {
            401, 403 -> "Key rejected — check your API key"
            413 -> "Recording too large"
            429 -> "Rate limited — retry soon"
            in 500..599 -> "Pocket is unavailable"
            else -> "Pocket error ${code()}"
        }

        is IOException -> "Upload failed"
        else -> "Something went wrong"
    }
}
