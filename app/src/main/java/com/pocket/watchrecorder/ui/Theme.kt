package com.pocket.watchrecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

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

@Composable
internal fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = PocketColors, content = content)
}
