package com.pocket.watchrecorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pocket.watchrecorder.audio.AudioRecorderManager
import com.pocket.watchrecorder.network.API_KEY
import com.pocket.watchrecorder.network.PocketClient
import com.pocket.watchrecorder.network.PocketPipelineException
import com.pocket.watchrecorder.upload.QueuedUpload
import com.pocket.watchrecorder.upload.UploadProgress
import com.pocket.watchrecorder.upload.UploadQueue
import com.pocket.watchrecorder.upload.UploadWorker
import com.pocket.watchrecorder.upload.uploadProgressFraction
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

// ===========================================================================
// Activity
// ===========================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PocketRecorderApp() }
    }
}

// ===========================================================================
// State
// ===========================================================================

enum class Phase(val label: String) {
    PROVISIONING("Preparing"),
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
 * Owns the recorder and follows every queued recording concurrently.
 *
 * The key difference from a single-job design: each queue entry gets its own
 * tracker coroutine, keyed by id. Starting a new recording no longer cancels
 * the one before it — that earlier recording keeps uploading, keeps polling,
 * and writes its summary into its own sidecar when it lands. The main screen
 * simply follows whichever entry is "focused"; the rest surface in the library.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "RecorderViewModel"
        const val WAKE_LOCK_TAG = "PocketWatch::pipeline"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        const val METER_INTERVAL_MS = 80L
        const val QUEUE_POLL_INTERVAL_MS = 1_200L
        const val LIBRARY_REFRESH_BUSY_MS = 1_000L
        const val LIBRARY_REFRESH_IDLE_MS = 4_000L
        const val MAX_UPLOAD_ATTEMPTS = 5
    }

    private val recorder = AudioRecorderManager(application)
    private val queue = UploadQueue(application)
    private val powerManager =
        application.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _library = MutableStateFlow<List<QueueItem>>(emptyList())
    val library: StateFlow<List<QueueItem>> = _library.asStateFlow()

    private val _route = MutableStateFlow<Route>(Route.Main)
    val route: StateFlow<Route> = _route.asStateFlow()

    private var meterJob: Job? = null
    private var lastDurationMs: Long = 0L

    /** One tracker per queue entry, keyed by entry id. */
    private val trackers = mutableMapOf<String, Job>()

    /** Which entry the main screen reflects. Null means "nothing in focus". */
    private var focusedId: String? = null

    private var workState: WorkInfo.State? = null
    private var workProgress: Float? = null

    init {
        viewModelScope.launch {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(UploadWorker.WORK_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    workState = info?.state
                    workProgress = info?.progress?.uploadProgressFraction()
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

        // Single supervisor: refreshes the library and guarantees that every
        // entry on disk has a tracker — including ones left over from a
        // previous launch.
        viewModelScope.launch {
            while (isActive) {
                val entries = queue.all()
                _library.value = entries.map { it.toItem() }
                entries.forEach { if (!it.isComplete) ensureTracked(it.id) }
                delay(
                    if (entries.any { !it.isComplete }) LIBRARY_REFRESH_BUSY_MS
                    else LIBRARY_REFRESH_IDLE_MS
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public intents
    // -----------------------------------------------------------------------

    fun onPrimaryAction() {
        if (recorder.isRecording) stopAndQueue() else startRecording()
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
        if (id != null) {
            val entry = queue.find(id)
            // Only reclaim space for something the user has actually seen.
            if (entry != null && (entry.isComplete || entry.isDeadLettered())) {
                discard(id)
            }
        }
        focusedId = null
        _state.value = UiState.Idle
        _route.value = Route.Main
    }

    /** Removes one recording and stops following it. */
    fun discard(id: String) {
        trackers.remove(id)?.cancel()
        queue.remove(id)
        _library.value = queue.all().map { it.toItem() }
        if (focusedId == id) {
            focusedId = null
            _state.value = UiState.Idle
        }
    }

    /**
     * Removes every finished recording, leaving anything still uploading or
     * summarizing alone. Scoped this way on purpose — the old blanket clear()
     * could delete audio that had never reached S3.
     */
    fun clearFinished() {
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
        _library.value = queue.all().map { it.toItem() }
    }

    fun retryFocused() {
        val id = focusedId ?: return dismissFocused()
        val entry = queue.find(id) ?: return dismissFocused()
        queue.update(entry.copy(attempts = 0, lastError = null))
        UploadWorker.schedule(getApplication())
        ensureTracked(id)
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    private fun startRecording() {
        val file = try {
            recorder.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open the microphone", t)
            _state.value = UiState.Failed("Mic unavailable", canRetry = false)
            return
        }
        Log.i(TAG, "Recording into ${file.name}")

        // Focus moves to the new take; earlier entries keep their own trackers.
        focusedId = null
        _route.value = Route.Main
        _state.value = UiState.Recording(0L, 0f)

        meterJob = viewModelScope.launch {
            while (isActive && recorder.isRecording) {
                _state.value = UiState.Recording(recorder.elapsedMillis, recorder.normalizedLevel())
                delay(METER_INTERVAL_MS)
            }
        }
    }

    private fun stopAndQueue() {
        meterJob?.cancel()
        // Read the length before stop() clears it — Pocket wants it at provisioning.
        lastDurationMs = recorder.elapsedMillis
        val file = recorder.stop()

        if (file == null) {
            _state.value = UiState.Failed("Too short — hold for a second", canRetry = false)
            return
        }

        val entry = queue.enqueue(
            audio = file,
            title = "Watch Recording",
            durationMs = lastDurationMs
        )
        focusedId = entry.id
        UploadWorker.schedule(getApplication())
        ensureTracked(entry.id)
        _library.value = queue.all().map { it.toItem() }
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
     * loop starts a fresh tracker from the sidecar.
     */
    private suspend fun track(entryId: String) {
        var entry = queue.find(entryId) ?: return
        if (entry.isComplete) {
            publish(entryId, UiState.Done(entry.summaryTitle, entry.summary!!))
            return
        }

        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        try {
            // --- Wait for the worker to land the bytes in S3 -----------------
            while (!entry.uploaded) {
                val failure = entry.lastError
                if (entry.isDeadLettered()) {
                    publish(entryId, UiState.Failed(failure ?: "Upload failed", canRetry = true))
                    return
                }

                publish(
                    entryId,
                    UiState.Working(
                        phase = Phase.UPLOADING,
                        progress = UploadProgress.fractionFor(entryId) ?: workProgress,
                        detail = when {
                            failure != null -> "retrying · ${entry.attempts}"
                            workState == WorkInfo.State.ENQUEUED -> "waiting for network"
                            else -> null
                        }
                    )
                )

                delay(QUEUE_POLL_INTERVAL_MS)
                entry = queue.find(entryId) ?: return
            }

            val recordingId = entry.recordingId
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

    /** Main-screen updates apply only to the entry the user is watching. */
    private fun publish(entryId: String, next: UiState) {
        if (entryId == focusedId) _state.value = next
    }

    private fun QueuedUpload.isDeadLettered(): Boolean =
        lastError != null && attempts >= MAX_UPLOAD_ATTEMPTS

    private fun QueuedUpload.toItem(): QueueItem = QueueItem(
        id = id,
        title = summaryTitle?.takeIf { it.isNotBlank() } ?: title,
        ageLabel = relativeAge(queuedAtEpochMs),
        status = when {
            isComplete -> ItemStatus.READY
            isDeadLettered() -> ItemStatus.FAILED
            uploaded -> ItemStatus.PROCESSING
            UploadProgress.fractionFor(id) != null -> ItemStatus.UPLOADING
            else -> ItemStatus.WAITING
        },
        progress = UploadProgress.fractionFor(id),
        summary = summary
    )

    override fun onCleared() {
        super.onCleared()
        meterJob?.cancel()
        recorder.cancel()
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is UnknownHostException -> "No connection"
        is SocketTimeoutException -> "Network timed out"
        is PocketPipelineException -> message ?: "Processing failed"
        is HttpException -> {
            // The server's own explanation is the useful part; the generic
            // label used to discard it. Trim it back once this is stable.
            val detail = runCatching { response()?.errorBody()?.string() }
                .getOrNull()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(90)
            when (code()) {
                401, 403 -> "HTTP ${code()} key=${API_KEY.take(6)}…${API_KEY.takeLast(4)} " +
                        "len=${API_KEY.length} ${detail ?: ""}"
                413 -> "Recording too large"
                429 -> "Rate limited — retry soon"
                in 500..599 -> "Pocket is unavailable"
                else -> "HTTP ${code()} ${detail ?: ""}"
            }
        }
        is IOException -> "Upload failed"
        else -> describeRootCause(this)
    }

    /**
     * Debug aid: walks to the deepest cause and reports its class + message.
     * Swap this back to a friendly string once the integration is stable.
     */
    private fun describeRootCause(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val name = root::class.java.simpleName.ifBlank { root::class.java.name }
        val detail = root.message?.takeIf { it.isNotBlank() }
        return if (detail != null) "$name: $detail" else name
    }
}

private fun relativeAge(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val seconds = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

// ===========================================================================
// Theme
// ===========================================================================

private val PocketColors = Colors(
    primary = Color(0xFFFF5A5F),
    primaryVariant = Color(0xFFB33F42),
    secondary = Color(0xFF7FD1FF),
    secondaryVariant = Color(0xFF3E8DB3),
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    error = Color(0xFFFF6B6B),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBDBDBD),
    onError = Color.Black
)

// ===========================================================================
// UI
// ===========================================================================

@Composable
fun PocketRecorderApp(viewModel: RecorderViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Screen-off on Wear suspends the process quickly; hold the screen while a
    // capture or a network run is in flight.
    val view = LocalView.current
    val keepAwake = state is UiState.Recording || state is UiState.Working
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    val listState = rememberScalingLazyListState()
    val scrolling = route !is Route.Main || state is UiState.Done

    MaterialTheme(colors = PocketColors) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { if (scrolling) PositionIndicator(listState) }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !hasMicPermission -> PermissionScreen(
                        denied = permissionRequested,
                        onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )

                    route is Route.Library -> LibraryScreen(
                        items = library,
                        listState = listState,
                        onOpen = viewModel::openDetail,
                        onDiscard = viewModel::discard,
                        onClearFinished = viewModel::clearFinished,
                        onBack = viewModel::backToMain
                    )

                    route is Route.Detail -> {
                        val item = library.firstOrNull { it.id == (route as Route.Detail).id }
                        SummaryScreen(
                            title = item?.title,
                            summary = item?.summary ?: "Still working on this one.",
                            listState = listState,
                            primaryLabel = "Back",
                            onPrimary = viewModel::openLibrary
                        )
                    }

                    else -> MainRoute(
                        state = state,
                        library = library,
                        listState = listState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun MainRoute(
    state: UiState,
    library: List<QueueItem>,
    listState: ScalingLazyListState,
    viewModel: RecorderViewModel
) {
    // Anything not currently on the main screen that still wants attention.
    val otherCount = library.count { it.status != ItemStatus.READY } +
            library.count { it.status == ItemStatus.READY && state !is UiState.Done }

    when (state) {
        is UiState.Idle -> IdleScreen(
            queuedCount = library.size,
            onStart = viewModel::onPrimaryAction,
            onOpenLibrary = viewModel::openLibrary
        )

        is UiState.Recording -> RecordingScreen(
            elapsedMs = state.elapsedMs,
            level = state.level,
            onStop = viewModel::onPrimaryAction
        )

        is UiState.Working -> WorkingScreen(
            state = state,
            otherCount = (otherCount - 1).coerceAtLeast(0),
            onOpenLibrary = viewModel::openLibrary,
            onRecordAnother = viewModel::onPrimaryAction
        )

        is UiState.Done -> SummaryScreen(
            title = state.title,
            summary = state.summary,
            listState = listState,
            primaryLabel = "New recording",
            onPrimary = viewModel::dismissFocused,
            secondaryLabel = if (library.size > 1) "Library (${library.size})" else null,
            onSecondary = viewModel::openLibrary
        )

        is UiState.Failed -> FailedScreen(
            message = state.message,
            canRetry = state.canRetry,
            onRetry = viewModel::retryFocused,
            onDismiss = viewModel::dismissFocused
        )
    }
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

@Composable
private fun PermissionScreen(denied: Boolean, onGrant: () -> Unit) {
    CenteredColumn {
        Text(
            text = if (denied) "Microphone access is off" else "Microphone needed",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (denied) "Enable it in Settings › Apps" else "To record and summarize",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Chip(
            label = { Text("Allow") },
            onClick = onGrant,
            colors = ChipDefaults.primaryChipColors()
        )
    }
}

@Composable
private fun IdleScreen(queuedCount: Int, onStart: () -> Unit, onOpenLibrary: () -> Unit) {
    CenteredColumn {
        Text(text = "Pocket", style = MaterialTheme.typography.title2)
        Text(
            text = "Tap to record",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        RecordButton(recording = false, level = 0f, onClick = onStart)

        if (queuedCount > 0) {
            Chip(
                label = { Text("$queuedCount in queue") },
                onClick = onOpenLibrary,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun RecordingScreen(elapsedMs: Long, level: Float, onStop: () -> Unit) {
    CenteredColumn {
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.display3,
            color = MaterialTheme.colors.onBackground
        )
        Text(
            text = "Recording",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        RecordButton(recording = true, level = level, onClick = onStop)
    }
}

@Composable
private fun WorkingScreen(
    state: UiState.Working,
    otherCount: Int,
    onOpenLibrary: () -> Unit,
    onRecordAnother: () -> Unit
) {
    CenteredColumn {
        Box(contentAlignment = Alignment.Center) {
            val progress = state.progress
            if (progress != null) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(58.dp),
                    strokeWidth = 5.dp,
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = MaterialTheme.colors.surface
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.caption1
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(58.dp),
                    strokeWidth = 5.dp,
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = MaterialTheme.colors.surface
                )
            }
        }

        Text(
            text = state.phase.label,
            style = MaterialTheme.typography.title3,
            modifier = Modifier.padding(top = 10.dp)
        )
        state.detail?.let { detail ->
            Text(
                text = detail.replaceFirstChar { it.uppercase(Locale.US) },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
        }

        // Uploading happens in the background, so starting another take is fine.
        Chip(
            label = { Text("Record another") },
            onClick = onRecordAnother,
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.padding(top = 10.dp)
        )
        if (otherCount > 0) {
            Chip(
                label = { Text("$otherCount more queued") },
                onClick = onOpenLibrary,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    items: List<QueueItem>,
    listState: ScalingLazyListState,
    onOpen: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onClearFinished: () -> Unit,
    onBack: () -> Unit
) {
    val finishedCount = items.count { it.status == ItemStatus.READY }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        anchorType = ScalingLazyListAnchorType.ItemStart,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 30.dp)
    ) {
        item {
            Text(
                text = "Recordings",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (items.isEmpty()) {
            item {
                Text(
                    text = "Nothing queued",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }

        items(items.size) { index ->
            val item = items[index]
            Chip(
                label = { Text(item.title, maxLines = 1) },
                secondaryLabel = {
                    Text(
                        text = buildString {
                            append(item.status.label)
                            item.progress?.let { append(" ${(it * 100).toInt()}%") }
                            if (item.ageLabel.isNotBlank()) append(" · ${item.ageLabel}")
                        },
                        maxLines = 1
                    )
                },
                onClick = {
                    if (item.status == ItemStatus.FAILED) onDiscard(item.id) else onOpen(item.id)
                },
                colors = if (item.status == ItemStatus.READY) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        if (finishedCount > 0) {
            item {
                Chip(
                    label = { Text("Clear finished ($finishedCount)") },
                    onClick = onClearFinished,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }

        item {
            Chip(
                label = { Text("Back") },
                onClick = onBack,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SummaryScreen(
    title: String?,
    summary: String,
    listState: ScalingLazyListState,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        anchorType = ScalingLazyListAnchorType.ItemStart,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 32.dp)
    ) {
        item {
            Text(
                text = title?.takeIf { it.isNotBlank() } ?: "Summary",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        item {
            Text(
                text = summary,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
        item {
            Chip(
                label = { Text(primaryLabel) },
                onClick = onPrimary,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
        if (secondaryLabel != null && onSecondary != null) {
            item {
                Chip(
                    label = { Text(secondaryLabel) },
                    onClick = onSecondary,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FailedScreen(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    CenteredColumn {
        Text(
            text = message,
            style = if (message.length > 40) {
                MaterialTheme.typography.caption2
            } else {
                MaterialTheme.typography.title3
            },
            color = MaterialTheme.colors.error,
            textAlign = TextAlign.Center,
            maxLines = 6,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (canRetry) {
            Chip(
                label = { Text("Retry") },
                onClick = onRetry,
                colors = ChipDefaults.primaryChipColors()
            )
        }
        Chip(
            label = { Text(if (canRetry) "Discard" else "OK") },
            onClick = onDismiss,
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

/**
 * The tactile control: a 72dp target (well above the 48dp Wear minimum),
 * haptic on every press, morphing between a filled circle and a stop square,
 * with a live input-level ring while recording.
 */
@Composable
private fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colors.primary
    val surface = MaterialTheme.colors.surface

    val morph by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "recordMorph"
    )
    val ring by animateFloatAsState(
        targetValue = if (recording) level else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "levelRing"
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(surface)
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f

            if (ring > 0.01f) {
                drawCircle(
                    color = accent.copy(alpha = 0.28f + 0.32f * ring),
                    radius = outerRadius - 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = (2.dp.toPx() + 5.dp.toPx() * ring))
                )
            }

            val idleRadius = outerRadius * 0.42f
            val stopSide = outerRadius * 0.62f
            val cornerPx = (idleRadius * (1f - morph)).coerceAtLeast(3.dp.toPx())

            if (morph < 0.02f) {
                drawCircle(color = accent, radius = idleRadius, center = center)
            } else {
                val side = idleRadius * 2f * (1f - morph) + stopSide * morph
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}