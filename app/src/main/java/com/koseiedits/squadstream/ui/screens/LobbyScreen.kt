package com.koseiedits.squadstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koseiedits.squadstream.StreamState
import com.koseiedits.squadstream.ui.theme.DiscordBackground
import com.koseiedits.squadstream.ui.theme.DiscordBlurple
import com.koseiedits.squadstream.ui.theme.DiscordCard
import com.koseiedits.squadstream.ui.theme.DiscordDock
import com.koseiedits.squadstream.ui.theme.DiscordGreen
import com.koseiedits.squadstream.ui.theme.DiscordRed
import com.koseiedits.squadstream.ui.theme.DiscordText
import com.koseiedits.squadstream.ui.theme.DiscordTextMuted
import com.koseiedits.squadstream.ui.theme.DiscordTextSecondary
import com.koseiedits.squadstream.ui.theme.avatarColorFor

/**
 * Discord-style lobby: the home screen when not in a room and not live.
 * Create / Join / Go Live Solo are the main focus; stream setup lives
 * in the settings sheet (gear icon, top right).
 */
@Composable
fun LobbyScreen(
    voiceName: String,
    onEditName: () -> Unit,
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onGoLiveSolo: () -> Unit,
    streamKeySet: Boolean,
    presetLabel: String,
    hudEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onBackToRoom: () -> Unit,
) {
    val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
    val voiceConnected by StreamState.voiceConnected.collectAsStateWithLifecycle()
    val voiceCode by StreamState.voiceRoomCode.collectAsStateWithLifecycle()
    val voicePeers by StreamState.voicePeers.collectAsStateWithLifecycle()
    val logLines by StreamState.log.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiscordBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- top bar: title + profile pill + settings ----
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
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(DiscordCard)
                    .clickable { onEditName() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(avatarColorFor(voiceName)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        voiceName.firstOrNull()?.uppercase() ?: "?",
                        color = DiscordText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    voiceName.ifBlank { "Set your name" },
                    color = DiscordText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings, contentDescription = "Settings",
                    tint = DiscordTextSecondary
                )
            }
        }

        // ---- back to the room / live, when minimised ----
        if ((voiceConnected || isStreaming)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DiscordDock),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBackToRoom() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isStreaming) "● LIVE" else "●",
                        color = if (isStreaming) DiscordRed else DiscordGreen,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (voiceConnected) "In room $voiceCode • ${voicePeers.size} mate${if (voicePeers.size == 1) "" else "s"}"
                        else "Streaming solo — tap to open the room",
                        color = DiscordText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text("Open ›", color = DiscordTextSecondary)
                }
            }
        }

        // ---- Card 1: create a squad room ----
        Card(
            colors = CardDefaults.cardColors(containerColor = DiscordBlurple),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = DiscordText)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Create Squad Room",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DiscordText
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start a voice room for your squad. Share the 6-letter code — up to 4 players.",
                    color = DiscordText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCreateRoom,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscordText),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create room", color = DiscordBlurple, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- Card 2: join a room ----
        Card(
            colors = CardDefaults.cardColors(containerColor = DiscordCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Group, contentDescription = null, tint = DiscordText)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Join Room",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DiscordText
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { onJoinCodeChange(it.uppercase()) },
                    label = { Text("Room code (6 characters)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = DiscordText,
                        unfocusedTextColor = DiscordText,
                        focusedContainerColor = DiscordDock,
                        unfocusedContainerColor = DiscordDock,
                        focusedLabelColor = DiscordTextSecondary,
                        unfocusedLabelColor = DiscordTextSecondary,
                    )
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onJoinRoom,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Join room", color = DiscordText, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- Card 3: go live solo ----
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
                        "Go Live Solo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DiscordText
                    )
                    Text(
                        "Stream to YouTube without a voice room.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DiscordTextSecondary
                    )
                }
                Button(
                    onClick = onGoLiveSolo,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple)
                ) {
                    Text("Go Live", color = DiscordText)
                }
            }
        }

        // ---- status chips ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (streamKeySet) "Key set" else "No key")
            StatusChip(presetLabel)
            StatusChip(if (hudEnabled) "Bubble ON" else "Bubble OFF")
        }

        // ---- recent activity: the error surface. A failed voice join
        // lands here ("Voice room couldn't connect…") so it is never
        // hidden behind a sheet. ----
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
