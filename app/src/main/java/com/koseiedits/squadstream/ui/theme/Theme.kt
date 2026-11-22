package com.koseiedits.squadstream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * SquadStream theme: forced dark Discord palette. The app never had a
 * Compose theme before — the old screen ran on the default light
 * Material 3 colours, which is why it looked white / light purple.
 */
private val DiscordScheme = darkColorScheme(
    primary = DiscordBlurple,
    onPrimary = DiscordText,
    primaryContainer = DiscordBlurple,
    onPrimaryContainer = DiscordText,
    secondary = DiscordGreen,
    onSecondary = DiscordText,
    background = DiscordBackground,
    onBackground = DiscordText,
    surface = DiscordDock,
    onSurface = DiscordText,
    surfaceVariant = DiscordCard,
    onSurfaceVariant = DiscordTextSecondary,
    error = DiscordRed,
    onError = DiscordText,
    outline = DiscordDivider,
)

@Composable
fun SquadStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DiscordScheme,
        content = content
    )
}
