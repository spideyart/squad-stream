package com.koseiedits.squadstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koseiedits.squadstream.BuildConfig
import com.koseiedits.squadstream.StreamState
import com.koseiedits.squadstream.ui.theme.DiscordBackground
import com.koseiedits.squadstream.ui.theme.DiscordBlurple
import com.koseiedits.squadstream.ui.theme.DiscordCard
import com.koseiedits.squadstream.ui.theme.DiscordDock
import com.koseiedits.squadstream.ui.theme.DiscordRed
import com.koseiedits.squadstream.ui.theme.DiscordText
import com.koseiedits.squadstream.ui.theme.DiscordTextMuted
import com.koseiedits.squadstream.ui.theme.DiscordTextSecondary

/**
 * Streaming-only lobby: the home screen.
 *
 * The squad voice room was removed on 2026-11-22 (voice over mobile data
 * needs a server-side relay; every free public relay we tried was dead).
 * Go Live streams the screen + game audio + mic to YouTube. Stream setup
 * lives in the settings sheet (gear icon, top right); the audio mixer
 * opens from the LIVE card while streaming.
 */
@Composable
fun LobbyScreen(
    onGoLive: () -> Unit,
    onOpenMixer: () -> Unit,
    streamKeySet: Boolean,
    presetLabel: String,
    hudEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
    val logLines by StreamState.log.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiscordBackground)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- top bar: title + settings ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SquadStream",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = DiscordText,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings, contentDescription = "Settings",
                    tint = DiscordTextSecondary
                )
            }
        }

        // ---- LIVE card, while streaming ----
        if (isStreaming) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DiscordDock),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "● LIVE",
                            color = DiscordRed,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            presetLabel,
                            color = DiscordTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Streaming to YouTube.",
                        color = DiscordText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onGoLive,
                            colors = ButtonDefaults.buttonColors(containerColor = DiscordRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop", color = DiscordText, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onOpenMixer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Mixer", color = DiscordText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // ---- Go Live card ----
            Card(
                colors = CardDefaults.cardColors(containerColor = DiscordCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ScreenShare, contentDescription = null, tint = DiscordTextSecondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Go Live",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DiscordText
                        )
                        Text(
                            "Stream your game to YouTube.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DiscordTextSecondary
                        )
                    }
                    Button(
                        onClick = onGoLive,
                        colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple)
                    ) {
                        Text("Go Live", color = DiscordText)
                    }
                }
            }
        }

        // ---- status chips ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (streamKeySet) "Key set" else "No key")
            StatusChip(presetLabel)
            StatusChip(if (hudEnabled) "Bubble ON" else "Bubble OFF")
            StatusChip(if (isStreaming) "● LIVE" else "Idle")
        }

        // ---- recent activity: the error surface. A failed stream start
        // lands here so it is never hidden behind a sheet. ----
        Text(
            "Recent activity",
            style = MaterialTheme.typography.titleMedium,
            color = DiscordText
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = DiscordCard),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                if (logLines.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = DiscordTextMuted)
                } else {
                    logLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = DiscordTextSecondary)
                    }
                }
            }
        }

        // Build stamp: the only way to tell on the phone which APK is
        // installed. The old and new builds looked identical until this
        // line existed, so a failed update left the old app running with
        // no visible difference.
        Spacer(Modifier.height(16.dp))
        Text(
            "SquadStream ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = DiscordTextMuted
        )
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DiscordDock)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = DiscordTextSecondary)
    }
}
