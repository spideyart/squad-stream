package com.koseiedits.squadstream.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

/**
 * Discord-style voice stage. Shows while inside a room (or live solo).
 * Participant grid, speaking rings, live telemetry, invite banner and
 * the bottom dock — mic, speaker, go live, mixer, leave.
 */
@Composable
fun VoiceRoomScreen(
    myName: String,
    presetLabel: String,
    onMinimize: () -> Unit,
    onCopyCode: () -> Unit,
    onShareCode: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleMic: () -> Unit,
    onGoLive: () -> Unit,
    onOpenMixer: () -> Unit,
    onLeave: () -> Unit,
) {
    val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
    val status by StreamState.status.collectAsStateWithLifecycle()
    val measured by StreamState.measuredBitrateBps.collectAsStateWithLifecycle()
    val startedAtMs by StreamState.startedAtMs.collectAsStateWithLifecycle()
    val voiceCode by StreamState.voiceRoomCode.collectAsStateWithLifecycle()
    val voiceConnected by StreamState.voiceConnected.collectAsStateWithLifecycle()
    val voicePeerMap by StreamState.voicePeerMap.collectAsStateWithLifecycle()
    val voiceLevels by StreamState.voiceLevels.collectAsStateWithLifecycle()
    val myVoiceLevel by StreamState.myVoiceLevel.collectAsStateWithLifecycle()
    val peerMuted by StreamState.peerMuted.collectAsStateWithLifecycle()
    val voiceMicMuted by StreamState.voiceMicMuted.collectAsStateWithLifecycle()
    val voiceSpeakerOn by StreamState.voiceSpeakerOn.collectAsStateWithLifecycle()

    // Participants: me first, then mates by name.
    val participants = remember(myName, voicePeerMap, voiceLevels, myVoiceLevel, peerMuted, voiceMicMuted) {
        val list = mutableListOf(
            Participant(
                id = "me", name = myName.ifBlank { "Player" },
                level = myVoiceLevel, muted = voiceMicMuted, isMe = true
            )
        )
        voicePeerMap.entries.sortedBy { it.value }.forEach { (id, name) ->
            list += Participant(
                id = id, name = name,
                level = voiceLevels[id] ?: 0f,
                muted = peerMuted[id] == true, isMe = false
            )
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiscordBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- top bar ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMinimize) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize",
                    tint = DiscordTextSecondary
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(DiscordCard)
                    .clickable { onCopyCode() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (voiceConnected) "Room $voiceCode" else "Solo stream",
                    color = DiscordText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (voiceConnected) {
                    Spacer(Modifier.width(4.dp))
                    Text("›", color = DiscordTextSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleSpeaker) {
                Icon(
                    if (voiceSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.Headset,
                    contentDescription = "Audio output",
                    tint = DiscordTextSecondary
                )
            }
            if (voiceConnected) {
                IconButton(onClick = onShareCode) {
                    Icon(
                        Icons.Filled.Share, contentDescription = "Invite squad",
                        tint = DiscordTextSecondary
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings, contentDescription = "Settings",
                    tint = DiscordTextSecondary
                )
            }
        }

        // ---- live telemetry pill (only while live or connecting) ----
        if (isStreaming || status != "Idle") {
            TelemetryPill(
                isStreaming = isStreaming,
                status = status,
                measuredBps = measured,
                startedAtMs = startedAtMs,
                presetLabel = presetLabel,
                onStop = onGoLive
            )
        }

        // ---- participant grid ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                participants.size == 1 -> {
                    ParticipantCard(participant = participants[0], isStreaming = isStreaming, modifier = Modifier.fillMaxWidth().weight(1f))
                }
                participants.size == 2 -> {
                    participants.forEach { p ->
                        ParticipantCard(participant = p, isStreaming = isStreaming, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
                else -> {
                    participants.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { p ->
                                ParticipantCard(participant = p, isStreaming = isStreaming, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---- invite banner (room not full yet) ----
        if (voiceConnected && participants.size < 4) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DiscordDock),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Invite Squad to Voice Chat",
                        color = DiscordText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onShareCode,
                        colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple)
                    ) { Text("Invite", color = DiscordText) }
                }
            }
        }

        // ---- bottom dock ----
        Card(
            colors = CardDefaults.cardColors(containerColor = DiscordDock),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DiscordTextMuted)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockButton(
                        icon = if (voiceMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (voiceMicMuted) "Muted" else "Mic",
                        tint = if (voiceMicMuted) DiscordRed else DiscordText,
                        onClick = onToggleMic
                    )
                    DockButton(
                        icon = if (voiceSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.Headset,
                        label = if (voiceSpeakerOn) "Speaker" else "Earpiece",
                        tint = DiscordText,
                        onClick = onToggleSpeaker
                    )
                    // Centre: Go Live / Stop Stream (bigger).
                    IconButton(
                        onClick = onGoLive,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) DiscordRed else DiscordBlurple)
                    ) {
                        Icon(
                            if (isStreaming) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                            contentDescription = if (isStreaming) "Stop stream" else "Go live",
                            tint = DiscordText,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    DockButton(
                        icon = Icons.Filled.Tune,
                        label = "Mixer",
                        tint = DiscordText,
                        onClick = onOpenMixer
                    )
                    DockButton(
                        icon = Icons.Filled.CallEnd,
                        label = "Leave",
                        tint = DiscordRed,
                        onClick = onLeave
                    )
                }
            }
        }
    }
}

private data class Participant(
    val id: String,
    val name: String,
    val level: Float,
    val muted: Boolean,
    val isMe: Boolean,
)

@Composable
private fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = DiscordTextSecondary)
    }
}

@Composable
private fun TelemetryPill(
    isStreaming: Boolean,
    status: String,
    measuredBps: Long,
    startedAtMs: Long,
    presetLabel: String,
    onStop: () -> Unit,
) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(isStreaming) {
        while (isStreaming) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = if (startedAtMs > 0) (now - startedAtMs) / 1000 else 0L
    val timer = "%02d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
    val mbps = measuredBps / 1_000_000f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DiscordDock)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isStreaming) "● LIVE" else "● $status",
            color = if (isStreaming) DiscordRed else DiscordTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Text(timer, color = DiscordText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text(presetLabel, color = DiscordTextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text(
            if (isStreaming) "%.1f Mbps".format(mbps) else "",
            color = DiscordTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (isStreaming) {
            Text(
                "Stop",
                color = DiscordRed,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onStop() }
            )
        }
    }
}

@Composable
private fun ParticipantCard(
    participant: Participant,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val speaking = participant.level > 0.03f && !participant.muted
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DiscordCard)
            .then(
                if (speaking) Modifier.border(3.dp, DiscordGreen, RoundedCornerShape(24.dp))
                else Modifier
            )
            .padding(14.dp)
    ) {
        // Muted mic icon, top right.
        if (participant.muted) {
            Icon(
                Icons.Filled.MicOff,
                contentDescription = "Muted",
                tint = DiscordRed,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(avatarColorFor(participant.name)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    participant.name.firstOrNull()?.uppercase() ?: "?",
                    color = DiscordText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            // Frosted bottom username pill.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (speaking) {
                    Text("● ", color = DiscordGreen, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    participant.name,
                    color = DiscordText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (participant.isMe && isStreaming) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "LIVE",
                        color = DiscordRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (speaking) {
                Text("speaking", color = DiscordGreen, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Level bar, bottom edge.
        if (speaking) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(participant.level.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiscordGreen)
            )
        }
    }
}
