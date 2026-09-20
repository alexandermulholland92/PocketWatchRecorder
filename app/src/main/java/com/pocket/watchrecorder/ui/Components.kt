package com.pocket.watchrecorder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.launch

/**
 * Crown / rotating-bezel scrolling for a list.
 *
 * Wear's primary scroll gesture is the rotary input, and a list that only
 * responds to touch feels broken on hardware. Built on the core Compose
 * `onRotaryScrollEvent` rather than a Wear-specific helper so it doesn't depend
 * on which Wear Compose version is in use.
 *
 * Apply the returned modifier to the scrollable itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberRotaryScroll(listState: ScalingLazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Rotary events only arrive at a focused node.
    LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
    }

    return Modifier
        .onRotaryScrollEvent { event ->
            scope.launch { listState.scrollBy(event.verticalScrollPixels) }
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}

/**
 * The tactile control: a 72dp target (well above the 48dp Wear minimum),
 * haptic on every press, morphing between a filled circle and a stop square,
 * with a live input-level ring while recording.
 */
@Composable
internal fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colors.primary
    val surface = MaterialTheme.colors.surface
    val label = if (recording) "Stop recording" else "Start recording"

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
            }
            // The button is a bare Canvas, so without this a screen reader has
            // nothing at all to announce for the app's only real control.
            .semantics { contentDescription = label },
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
internal fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
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
