package com.koseiedits.squadstream.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Discord dark palette (from the UI checklist). Locked everywhere —
 * no dynamic / Material You tinting, so the gaming UI looks the same
 * on every Android phone.
 */
val DiscordBackground = Color(0xFF0B0C0E) // app background
val DiscordDock = Color(0xFF1E1F22) // bottom dock / sheets
val DiscordCard = Color(0xFF2B2D31) // player cards / inputs
val DiscordBlurple = Color(0xFF5865F2) // primary button accent
val DiscordGreen = Color(0xFF23A55A) // speaking ring / online
val DiscordRed = Color(0xFFDA373C) // live badge / disconnect / muted
val DiscordText = Color(0xFFF2F3F5) // primary text
val DiscordTextSecondary = Color(0xFFB5BAC1) // secondary text
val DiscordTextMuted = Color(0xFF80848E) // muted text
val DiscordDivider = Color(0xFF3F4147) // hairlines

/** Avatar ring colours, picked by name hash (Discord-style). */
val AvatarColors = listOf(
    Color(0xFF5865F2),
    Color(0xFF57F287),
    Color(0xFFFEE75C),
    Color(0xFFEB459E),
    Color(0xFFED4245),
    Color(0xFFF47FFF),
    Color(0xFF57C8F2),
    Color(0xFFF38A3C),
)

fun avatarColorFor(name: String): Color {
    if (name.isEmpty()) return AvatarColors[0]
    return AvatarColors[(name.hashCode() and Int.MAX_VALUE) % AvatarColors.size]
}
