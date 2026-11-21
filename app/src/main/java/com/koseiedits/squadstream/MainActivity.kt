package com.koseiedits.squadstream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // Form state lives in the Activity so the service-start flow can read it.
    // The stream key is kept in memory only — it is never saved to disk.
    private var rtmpUrl by mutableStateOf("rtmp://a.rtmp.youtube.com/live2")
    private var streamKey by mutableStateOf("")
    private var selectedPreset by mutableStateOf(StreamConfig.PRESETS[0])
    private var audioMode by mutableStateOf(AudioMode.MIX)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) launchCaptureConsent()
        else toast("Mic + notification permission needed to stream")
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startStreamService(result.resultCode, data)
        } else {
            toast("Screen capture denied — stream not started")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SquadStreamScreen() }
    }

    @Composable
    private fun SquadStreamScreen() {
        val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
        val status by StreamState.status.collectAsStateWithLifecycle()
        val measured by StreamState.measuredBitrateBps.collectAsStateWithLifecycle()
        val encoderBps by StreamState.encoderBitrateBps.collectAsStateWithLifecycle()
        val logLines by StreamState.log.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SquadStream", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Screen + game audio + mic → YouTube Live (RTMP)",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
            if (isStreaming) {
                Text(
                    "Upload: ${measured / 1000} kbps • Encoder: ${encoderBps / 1000} kbps",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = rtmpUrl,
                onValueChange = { rtmpUrl = it },
                label = { Text("RTMP URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isStreaming,
                singleLine = true
            )
            OutlinedTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                label = { Text("YouTube stream key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isStreaming,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Text("Quality", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamConfig.PRESETS.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.label) },
                        enabled = !isStreaming
                    )
                }
            }

            Text("Audio", style = MaterialTheme.typography.titleMedium)
            AudioMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = audioMode == mode,
                        onClick = { audioMode = mode },
                        enabled = !isStreaming
                    )
                    Text(
                        when (mode) {
                            AudioMode.MIX -> "Mic + game audio (mix)"
                            AudioMode.INTERNAL_ONLY -> "Game audio only"
                            AudioMode.MIC_ONLY -> "Mic only"
                        }
                    )
                }
            }

            Button(
                onClick = { if (isStreaming) onStopTapped() else onStartTapped() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color(0xFFC62828) else Color(0xFF2E7D32)
                )
            ) {
                Text(if (isStreaming) "STOP STREAM" else "START STREAM")
            }

            Text("Log", style = MaterialTheme.typography.titleMedium)
            logLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "Stream key is never saved. Get it in YouTube Studio → Go Live → " +
                    "Stream settings. Keep it private — anyone with it can stream to your channel.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    private fun onStartTapped() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else launchCaptureConsent()
    }

    private fun launchCaptureConsent() {
        // Android 14 rule: get the user's screen-capture consent BEFORE
        // the foreground service starts. The service then calls startForeground
        // (mediaProjection|microphone) before getMediaProjection().
        captureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startStreamService(resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, data)
            putExtra(StreamService.EXTRA_RTMP_URL, rtmpUrl.trim())
            putExtra(StreamService.EXTRA_STREAM_KEY, streamKey.trim())
            putExtra(StreamService.EXTRA_WIDTH, selectedPreset.width)
            putExtra(StreamService.EXTRA_HEIGHT, selectedPreset.height)
            putExtra(StreamService.EXTRA_FPS, selectedPreset.fps)
            putExtra(StreamService.EXTRA_VIDEO_BITRATE, selectedPreset.videoBitrateBps)
            putExtra(StreamService.EXTRA_AUDIO_MODE, audioMode.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onStopTapped() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        startService(intent)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
