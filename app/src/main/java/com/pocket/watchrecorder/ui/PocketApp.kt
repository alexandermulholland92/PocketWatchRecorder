package com.pocket.watchrecorder.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.pocket.watchrecorder.ApiKeyState
import com.pocket.watchrecorder.ItemStatus
import com.pocket.watchrecorder.QueueItem
import com.pocket.watchrecorder.RecorderViewModel
import com.pocket.watchrecorder.Route
import com.pocket.watchrecorder.UiState

/**
 * Permissions the app asks for up front.
 *
 * POST_NOTIFICATIONS is here because capture now runs in a foreground service:
 * without it the ongoing notification is suppressed on API 33+, which makes a
 * recording in progress invisible to the user.
 */
private val requiredPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

@Composable
fun PocketRecorderApp(viewModel: RecorderViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val keyState by viewModel.apiKeyState.collectAsStateWithLifecycle()
    val bridgeState by viewModel.bridgeState.collectAsStateWithLifecycle()

    var hasMicPermission by remember { mutableStateOf(context.hasMicPermission()) }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMicPermission = context.hasMicPermission()
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) permissionLauncher.launch(requiredPermissions)
    }

    // Note: no keepScreenOn here any more. Capture survives the screen turning
    // off because RecordingService holds the microphone as a foreground
    // service, and uploads survive it because UploadWorker promotes itself the
    // same way. Forcing the display on was a workaround for neither being true.

    val enterApiKey = rememberApiKeyEntry(onEntered = viewModel::saveApiKey)

    val listState = rememberScalingLazyListState()
    val currentRoute = route
    val scrolling = currentRoute !is Route.Main || state is UiState.Done

    PocketTheme {
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
                        onGrant = { permissionLauncher.launch(requiredPermissions) }
                    )

                    currentRoute is Route.Library -> LibraryScreen(
                        items = library,
                        listState = listState,
                        onOpen = viewModel::openDetail,
                        onDiscard = viewModel::discard,
                        onClearFinished = viewModel::clearFinished,
                        onBack = viewModel::backToMain
                    )

                    currentRoute is Route.Settings -> SettingsScreen(
                        keyState = keyState,
                        bridgeState = bridgeState,
                        listState = listState,
                        onEnterKey = enterApiKey,
                        onClearKey = viewModel::clearApiKey,
                        onStartBridge = viewModel::startKeyboardBridge,
                        onStopBridge = viewModel::stopKeyboardBridge,
                        onBack = viewModel::backToMain
                    )

                    currentRoute is Route.Detail -> {
                        val item = library.firstOrNull { it.id == currentRoute.id }
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
                        keyState = keyState,
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
    keyState: ApiKeyState,
    listState: ScalingLazyListState,
    viewModel: RecorderViewModel
) {
    // Anything not currently on the main screen that still wants attention.
    val otherCount = library.count { it.status != ItemStatus.READY } +
            library.count { it.status == ItemStatus.READY && state !is UiState.Done }

    when (state) {
        is UiState.Idle -> IdleScreen(
            queuedCount = library.size,
            keyState = keyState,
            onStart = viewModel::onPrimaryAction,
            onOpenLibrary = viewModel::openLibrary,
            onOpenSettings = viewModel::openSettings
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
            onRecordAnother = viewModel::onPrimaryAction,
            onRetry = viewModel::retryFocused
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
