package com.cyma.videoloop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Signage boxes run in dark rooms and cut between full-bleed images/videos.
// A light scheme flashes white on every transition, so the whole app is themed
// dark: black behind content, white type, dark-grey for the pop-up cards
// (WiFi-setup overlay + download dialog) that surface over playback.
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val DarkGrey = Color(0xFF2C2C2C)       // cards / "notifications"
private val DarkGreyVariant = Color(0xFF3A3A3A)
private val OffWhite = Color(0xFFE0E0E0)

private val CymaDarkColors = darkColorScheme(
    background = Black,
    onBackground = White,
    surface = DarkGrey,
    onSurface = White,
    surfaceVariant = DarkGreyVariant,
    onSurfaceVariant = OffWhite,
    // White progress spinners / accents read cleanly on black.
    primary = White,
    onPrimary = Black,
)

@Composable
fun CymaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CymaDarkColors, content = content)
}
