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
import com.pocket.watchrecorder.audio.AudioRecorderManager
import com.pocket.watchrecorder.network.API_KEY
import com.pocket.watchrecorder.network.PocketClient
import com.pocket.watchrecorder.network.PocketPipelineException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.File
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

// ===========================================================================
// ViewModel — owns the recorder and the upload/poll pipeline
// ===========================================================================

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "RecorderViewModel"
        const val WAKE_LOCK_TAG = "PocketWatch::pipeline"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        const val METER_INTERVAL_MS = 80L
    }

    private val recorder = AudioRecorderManager(application)
    private val powerManager =
        application.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var meterJob: Job? = null
    private var pipelineJob: Job? = null

    private var pendingFile: File? = null
    private var recordingId: String? = null
    private var uploadComplete = false

    // -----------------------------------------------------------------------
    // Public intents
    // -----------------------------------------------------------------------

    fun onPrimaryAction() {
        if (recorder.isRecording) stopAndProcess() else startRecording()
    }

    fun retry() {
        val file = pendingFile
        when {
            uploadComplete && recordingId != null -> runPipeline(null)
            file != null && file.exists() -> runPipeline(file)
            else -> reset()
        }
    }

    fun reset() {
        pipelineJob?.cancel()
        meterJob?.cancel()
        recorder.cancel()
        pendingFile?.delete()
        pendingFile = null
        recordingId = null
        uploadComplete = false
        recorder.purgeAll()
        _state.value = UiState.Idle
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    private fun startRecording() {
        pipelineJob?.cancel()
        recordingId = null
        uploadComplete = false
        pendingFile?.delete()
        pendingFile = null

        val file = try {
            recorder.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open the microphone", t)
            _state.value = UiState.Failed("Mic unavailable", canRetry = false)
            return
        }

        pendingFile = file
        _state.value = UiState.Recording(0L, 0f)

        meterJob = viewModelScope.launch {
            while (isActive && recorder.isRecording) {
                _state.value = UiState.Recording(recorder.elapsedMillis, recorder.normalizedLevel())
                delay(METER_INTERVAL_MS)
            }
        }
    }

    private fun stopAndProcess() {
        meterJob?.cancel()
        val file = recorder.stop()

        if (file == null) {
            pendingFile = null
            _state.value = UiState.Failed("Too short — hold for a second", canRetry = false)
            return
        }

        pendingFile = file
        runPipeline(file)
    }

    // -----------------------------------------------------------------------
    // Provision -> upload -> poll
    // -----------------------------------------------------------------------

    /**
     * Runs the remote half of the workflow.
     *
     * Passing `null` for [file] resumes an interrupted run that already got as
     * far as a successful S3 PUT, so a retry only re-polls.
     *
     * Reliability note: this lives in [viewModelScope] rather than a foreground
     * service because the whole run is bounded (seconds of upload, minutes of
     * polling) and the activity stays in the foreground task. The
     * PARTIAL_WAKE_LOCK is the part that actually matters on Wear — without it
     * the CPU suspends on wrist-down and the poll loop stalls until the user
     * raises their arm. If you need the run to survive the app being swept out
     * of memory, swap this body for a WorkManager expedited request keyed on
     * [recordingId] and observe WorkInfo here instead.
     */
    private fun runPipeline(file: File?) {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

            try {
                if (!uploadComplete) {
                    requireNotNull(file) { "No audio file to upload" }

                    // --- Step 2: provision -----------------------------------
                    _state.value = UiState.Working(Phase.PROVISIONING)
                    val provision = PocketClient.createUpload(
                        fileName = file.name,
                        contentType = AudioRecorderManager.CONTENT_TYPE,
                        title = "Watch Recording"
                    )
                    recordingId = provision.resolvedRecordingId

                    // --- Step 3: PUT to S3 -----------------------------------
                    _state.value = UiState.Working(Phase.UPLOADING, progress = 0f)
                    PocketClient.uploadRecording(
                        uploadUrl = provision.resolvedUploadUrl!!,
                        file = file,
                        contentType = AudioRecorderManager.CONTENT_TYPE
                    ) { progress ->
                        _state.value = UiState.Working(Phase.UPLOADING, progress = progress)
                    }

                    uploadComplete = true
                    // Audio is safely in S3; reclaim the watch's storage.
                    recorder.purgeAll()
                    pendingFile = null
                }

                // --- Step 4: poll for the summary ---------------------------
                val id = requireNotNull(recordingId) { "Missing recording id" }
                _state.value = UiState.Working(Phase.PROCESSING)

                val recording = PocketClient.awaitProcessing(id) { status ->
                    _state.value = UiState.Working(Phase.PROCESSING, detail = status)
                }

                _state.value = UiState.Done(
                    title = recording.title,
                    summary = recording.summaryText
                        ?: recording.transcriptText
                        ?: "Processed, but no summary text was returned."
                )
            } catch (timeout: TimeoutCancellationException) {
                // TimeoutCancellationException IS a CancellationException, so it
                // must be caught before the generic cancellation rethrow below.
                Log.w(TAG, "Timed out waiting for the summary", timeout)
                _state.value = UiState.Failed("Still processing — check back", canRetry = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "Pipeline failed", t)
                _state.value = UiState.Failed(t.toUserMessage(), canRetry = true)
            } finally {
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
            }
        }
    }

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
     *
     * ExceptionInInitializerError / NoClassDefFoundError both carry a null
     * message, so the generic fallback used to render as "Something went
     * wrong" and hide the only useful detail. Swap this back to a friendly
     * string once the integration is stable.
     */
    private fun describeRootCause(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val name = root::class.java.simpleName.ifBlank { root::class.java.name }
        val detail = root.message?.takeIf { it.isNotBlank() }
        return if (detail != null) "$name: $detail" else name
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

    MaterialTheme(colors = PocketColors) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { if (state is UiState.Done) PositionIndicator(listState) }
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

                    else -> when (val current = state) {
                        is UiState.Idle -> IdleScreen(onStart = viewModel::onPrimaryAction)

                        is UiState.Recording -> RecordingScreen(
                            elapsedMs = current.elapsedMs,
                            level = current.level,
                            onStop = viewModel::onPrimaryAction
                        )

                        is UiState.Working -> WorkingScreen(current)

                        is UiState.Done -> DoneScreen(
                            title = current.title,
                            summary = current.summary,
                            listState = listState,
                            onReset = viewModel::reset
                        )

                        is UiState.Failed -> FailedScreen(
                            message = current.message,
                            canRetry = current.canRetry,
                            onRetry = viewModel::retry,
                            onDismiss = viewModel::reset
                        )
                    }
                }
            }
        }
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
private fun IdleScreen(onStart: () -> Unit) {
    CenteredColumn {
        Text(
            text = "Pocket",
            style = MaterialTheme.typography.title2
        )
        Text(
            text = "Tap to record",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        RecordButton(recording = false, level = 0f, onClick = onStart)
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
private fun WorkingScreen(state: UiState.Working) {
    CenteredColumn {
        Box(contentAlignment = Alignment.Center) {
            val progress = state.progress
            if (progress != null) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(64.dp),
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
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 5.dp,
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = MaterialTheme.colors.surface
                )
            }
        }

        Text(
            text = state.phase.label,
            style = MaterialTheme.typography.title3,
            modifier = Modifier.padding(top = 12.dp)
        )
        state.detail?.let { detail ->
            Text(
                text = detail.replaceFirstChar { it.uppercase(Locale.US) },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoneScreen(
    title: String?,
    summary: String,
    listState: ScalingLazyListState,
    onReset: () -> Unit
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        anchorType = ScalingLazyListAnchorType.ItemStart,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 32.dp
        )
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
                label = { Text("New recording") },
                onClick = onReset,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
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

            // Input-level ring (only meaningful while recording).
            if (ring > 0.01f) {
                drawCircle(
                    color = accent.copy(alpha = 0.28f + 0.32f * ring),
                    radius = outerRadius - 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = (2.dp.toPx() + 5.dp.toPx() * ring))
                )
            }

            // Idle = filled circle. Recording = rounded square (stop affordance).
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