package com.remcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RemCamColorScheme = darkColorScheme(
    primary = RemCamAccent,
    onPrimary = RemCamOnAccent,
    primaryContainer = Color(0xFF3A2F10),
    onPrimaryContainer = RemCamAccent,
    secondary = RemCamGreen,
    onSecondary = RemCamBlack,
    secondaryContainer = Color(0xFF123326),
    onSecondaryContainer = RemCamGreen,
    tertiary = RemCamAmber,
    onTertiary = RemCamBlack,
    background = RemCamBlack,
    onBackground = RemCamText,
    surface = RemCamBar,
    onSurface = RemCamText,
    surfaceVariant = RemCamSurface,
    onSurfaceVariant = RemCamTextDim,
    surfaceContainer = RemCamBar,
    surfaceContainerHigh = RemCamSurface,
    surfaceContainerHighest = RemCamSurfaceHigh,
    outline = Color(0xFF46515B),
    outlineVariant = Color(0xFF2A333B),
    error = RemCamRed,
    onError = RemCamBlack,
    errorContainer = RemCamErrorContainer,
    onErrorContainer = RemCamText,
    scrim = Color(0xCC000000)
)

@Composable
fun RemCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemCamColorScheme,
        typography = RemCamTypography,
        content = content
    )
}
