package com.koseiedits.squadstream.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koseiedits.squadstream.StreamState
import com.koseiedits.squadstream.ui.theme.DiscordDock
import com.koseiedits.squadstream.ui.theme.DiscordText
import com.koseiedits.squadstream.ui.theme.DiscordTextSecondary

/**
 * 2-channel audio mixer sheet.
 *
 * Game + My Mic are real sliders (they drive the live mix — 0 means that
 * channel is off, exactly like the HUD sliders).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMixerSheet(
    onMicGainChange: (Int) -> Unit,
    onGameGainChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val micGain by StreamState.micGain.collectAsStateWithLifecycle()
    val gameGain by StreamState.gameGain.collectAsStateWithLifecycle()

    val modeLabel = when {
        micGain > 0 && gameGain > 0 -> "Mic + game audio"
        micGain == 0 -> "Game audio only"
        else -> "Mic only"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DiscordDock,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Audio mixer",
                style = MaterialTheme.typography.titleLarge,
                color = DiscordText
            )
            Text(
                "Now: $modeLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = DiscordTextSecondary
            )

            MixerRow(
                label = "Game volume",
                gain = gameGain,
                onGainChange = onGameGainChange
            )
            MixerRow(
                label = "My mic",
                gain = micGain,
                onGainChange = onMicGainChange
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MixerRow(
    label: String,
    gain: Int,
    onGainChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = DiscordText,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onGainChange(if (gain > 0) 0 else 100) }) {
                Icon(
                    if (gain > 0) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (gain > 0) "Mute $label" else "Unmute $label",
                    tint = DiscordText
                )
            }
        }
        Slider(
            value = gain.toFloat(),
            onValueChange = { onGainChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
