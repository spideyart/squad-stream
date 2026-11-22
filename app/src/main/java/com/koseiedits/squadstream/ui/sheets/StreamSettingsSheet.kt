package com.koseiedits.squadstream.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koseiedits.squadstream.StreamConfig
import com.koseiedits.squadstream.ui.theme.DiscordCard
import com.koseiedits.squadstream.ui.theme.DiscordDock
import com.koseiedits.squadstream.ui.theme.DiscordText
import com.koseiedits.squadstream.ui.theme.DiscordTextSecondary

/**
 * Stream settings, moved out of the main screen: RTMP URL, masked
 * stream key (+ eye), quality chips and the floating-bubble switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSheet(
    rtmpUrl: String,
    onRtmpUrlChange: (String) -> Unit,
    streamKey: String,
    onStreamKeyChange: (String) -> Unit,
    isStreaming: Boolean,
    presets: List<StreamConfig>,
    selectedPreset: StreamConfig,
    onPresetChange: (StreamConfig) -> Unit,
    hudEnabled: Boolean,
    overlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onToggleHud: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyVisible by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Stream settings",
                style = MaterialTheme.typography.titleLarge,
                color = DiscordText
            )

            OutlinedTextField(
                value = rtmpUrl,
                onValueChange = onRtmpUrlChange,
                label = { Text("RTMP URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isStreaming,
                singleLine = true,
                colors = sheetFieldColors()
            )
            OutlinedTextField(
                value = streamKey,
                onValueChange = onStreamKeyChange,
                label = { Text("YouTube stream key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isStreaming,
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                            tint = DiscordTextSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = sheetFieldColors()
            )
            Text(
                "Stream key is never saved. Get it in YouTube Studio → Go Live → " +
                    "Stream settings. Keep it private — anyone with it can stream to your channel.",
                style = MaterialTheme.typography.bodySmall,
                color = DiscordTextSecondary
            )

            Text("Video quality", style = MaterialTheme.typography.titleMedium, color = DiscordText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { onPresetChange(preset) },
                        label = { Text(preset.label) },
                        enabled = !isStreaming
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("In-Game Floating Bubble", style = MaterialTheme.typography.bodyLarge, color = DiscordText)
                    Text(
                        "A bubble floats over your game to control the stream without leaving it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DiscordTextSecondary
                    )
                    if (!overlayGranted) {
                        Text(
                            "Needs the 'Display over other apps' permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DiscordTextSecondary
                        )
                    }
                }
                Switch(
                    checked = hudEnabled,
                    onCheckedChange = { on ->
                        if (on && !overlayGranted) onRequestOverlay()
                        else onToggleHud(on)
                    }
                )
            }
        }
    }
}

@Composable
private fun sheetFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = DiscordText,
    unfocusedTextColor = DiscordText,
    disabledTextColor = DiscordTextSecondary,
    focusedContainerColor = DiscordCard,
    unfocusedContainerColor = DiscordCard,
    disabledContainerColor = DiscordCard,
    focusedLabelColor = DiscordTextSecondary,
    unfocusedLabelColor = DiscordTextSecondary,
)
