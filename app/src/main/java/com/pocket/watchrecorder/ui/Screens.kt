package com.pocket.watchrecorder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.pocket.watchrecorder.ApiKeyState
import com.pocket.watchrecorder.ItemStatus
import com.pocket.watchrecorder.QueueItem
import com.pocket.watchrecorder.UiState
import java.util.Locale

@Composable
internal fun PermissionScreen(denied: Boolean, onGrant: () -> Unit) {
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
internal fun IdleScreen(
    queuedCount: Int,
    keyState: ApiKeyState,
    onStart: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val keyMissing = keyState == ApiKeyState.MISSING

    CenteredColumn {
        Text(text = "Pocket", style = MaterialTheme.typography.title2)
        Text(
            text = if (keyMissing) "No key — uploads off" else "Tap to record",
            style = MaterialTheme.typography.caption1,
            color = if (keyMissing) {
                MaterialTheme.colors.error
            } else {
                MaterialTheme.colors.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        RecordButton(recording = false, level = 0f, onClick = onStart)

        // Surfaced on the first screen rather than buried: without a key the
        // app records happily and then fails every upload, which is a
        // confusing way to find out.
        if (keyMissing) {
            Chip(
                label = { Text("Add your key") },
                onClick = onOpenSettings,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.padding(top = 10.dp)
            )
        } else if (queuedCount > 0) {
            Chip(
                label = { Text("$queuedCount in queue") },
                onClick = onOpenLibrary,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.padding(top = 10.dp)
            )
        } else {
            Chip(
                label = { Text("Settings") },
                onClick = onOpenSettings,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
internal fun RecordingScreen(elapsedMs: Long, level: Float, onStop: () -> Unit) {
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
internal fun WorkingScreen(
    state: UiState.Working,
    otherCount: Int,
    onOpenLibrary: () -> Unit,
    onRecordAnother: () -> Unit,
    onRetry: () -> Unit
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

        // An upload that is failing or being held back used to leave the user
        // with nothing to do but wait out five attempts, so give them the same
        // escape hatch the failure screen has.
        if (state.retryable) {
            Chip(
                label = { Text("Retry now") },
                onClick = onRetry,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // Uploading happens in the background, so starting another take is fine.
        Chip(
            label = { Text("Record another") },
            onClick = onRecordAnother,
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.padding(top = if (state.retryable) 4.dp else 10.dp)
        )

        // Dropped while retryable, to keep this screen to two chips on a
        // round display — it is the least useful of the three just then.
        if (otherCount > 0 && !state.retryable) {
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
internal fun LibraryScreen(
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
        modifier = Modifier
            .fillMaxSize()
            .then(rememberRotaryScroll(listState)),
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
internal fun SummaryScreen(
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
        modifier = Modifier
            .fillMaxSize()
            .then(rememberRotaryScroll(listState)),
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
internal fun FailedScreen(
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

@Composable
internal fun SettingsScreen(
    keyState: ApiKeyState,
    listState: ScalingLazyListState,
    onEnterKey: () -> Unit,
    onClearKey: () -> Unit,
    onBack: () -> Unit
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(rememberRotaryScroll(listState)),
        anchorType = ScalingLazyListAnchorType.ItemStart,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 30.dp)
    ) {
        item {
            Text(
                text = "Pocket key",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        item {
            Text(
                text = when (keyState) {
                    ApiKeyState.ON_DEVICE -> "Saved on this watch"
                    ApiKeyState.FROM_BUILD -> "Using the key built into this app"
                    ApiKeyState.MISSING -> "Not set — uploads will fail"
                    ApiKeyState.STORAGE_FAILED -> "Could not be saved on this watch"
                },
                style = MaterialTheme.typography.caption1,
                color = when (keyState) {
                    ApiKeyState.MISSING, ApiKeyState.STORAGE_FAILED -> MaterialTheme.colors.error
                    else -> MaterialTheme.colors.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Chip(
                label = {
                    Text(if (keyState == ApiKeyState.ON_DEVICE) "Replace key" else "Enter key")
                },
                onClick = onEnterKey,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        if (keyState == ApiKeyState.ON_DEVICE) {
            item {
                Chip(
                    label = { Text("Remove key") },
                    onClick = onClearKey,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
        }

        item {
            Text(
                text = "Entering it here keeps the key off every build of the app.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        item {
            Chip(
                label = { Text("Back") },
                onClick = onBack,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
