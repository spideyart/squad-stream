package com.koseiedits.squadstream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    companion object {
        /** Set by the HUD bubble's START button: open the app and start streaming. */
        const val EXTRA_AUTO_START_CAPTURE = "auto_start_capture"
    }

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val prefs by lazy { getSharedPreferences(HudService.PREFS, Context.MODE_PRIVATE) }

    // Form state lives in the Activity so the service-start flow can read it.
    // The stream key is kept in memory only — it is never saved to disk.
    private var rtmpUrl by mutableStateOf("rtmp://a.rtmp.youtube.com/live2")
    private var streamKey by mutableStateOf("")
    private var selectedPreset by mutableStateOf(StreamConfig.PRESETS[0])
    private var audioMode by mutableStateOf(AudioMode.MIX)

    // Phase 2 — floating controls state.
    private var hudEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)

    // Phase 3 — squad voice room settings. The Supabase URL + anon key live
    // in SharedPreferences only — never in code, never in git.
    private var supabaseUrl by mutableStateOf("")
    private var supabaseKey by mutableStateOf("")
    private var voiceName by mutableStateOf("")
    private var joinCode by mutableStateOf("")
    private var pendingVoiceCode: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) launchCaptureConsent()
        else toast("Mic + notification permission needed to stream")
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingVoiceCode?.let { startVoiceService(it) }
            pendingVoiceCode = null
        } else {
            toast("Mic permission needed for the voice room")
        }
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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Returning from Settings: re-check, the user may have granted it.
        refreshHudState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshHudState()
        supabaseUrl = prefs.getString("supabase_url", "").orEmpty()
        supabaseKey = prefs.getString("supabase_key", "").orEmpty()
        voiceName = prefs.getString("voice_name", "").orEmpty()
        // If the bubble's START button opened us, restore the bubble if the
        // user had it on (the service may have been killed while gaming).
        if (hudEnabled && overlayGranted) startHudService()
        setContent { SquadStreamScreen() }
        // Defer the auto-start so the capture/permission launchers fire
        // after the activity is fully created, not inside onCreate.
        window.decorView.post { handleAutoStartIntent(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStartIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshHudState()
    }

    /**
     * The HUD bubble's START button opens the app with this extra. Start the
     * stream at once when a key is already pasted; otherwise just show the
     * form so the user can paste the key first.
     */
    private fun handleAutoStartIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_CAPTURE, false) != true) return
        intent.removeExtra(EXTRA_AUTO_START_CAPTURE)
        if (streamKey.isBlank()) {
            toast("Paste your stream key first, then tap START")
            return
        }
        onStartTapped()
    }

    private fun refreshHudState() {
        hudEnabled = prefs.getBoolean(HudService.PREF_HUD_ENABLED, false)
        overlayGranted = Settings.canDrawOverlays(this)
    }

    // ---- Phase 3: squad voice room ----

    private fun saveVoicePrefs() {
        prefs.edit()
            .putString("supabase_url", supabaseUrl.trim())
            .putString("supabase_key", supabaseKey.trim())
            .putString("voice_name", voiceName.trim())
            .apply()
    }

    private fun onCreateRoomTapped() {
        val code = RoomCodes.generate()
        joinCode = code
        startVoiceJoin(code)
    }

    private fun onJoinRoomTapped() {
        val code = joinCode.trim().uppercase()
        if (!RoomCodes.isValid(code)) {
            toast("Room code is 6 characters, A–Z and 2–9")
            return
        }
        startVoiceJoin(code)
    }

    private fun startVoiceJoin(code: String) {
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            toast("Paste the Supabase URL + anon key first")
            return
        }
        saveVoicePrefs()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceCode = code
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceService(code)
    }

    private fun startVoiceService(code: String) {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_JOIN
            putExtra(VoiceService.EXTRA_SUPABASE_URL, supabaseUrl.trim())
            putExtra(VoiceService.EXTRA_SUPABASE_KEY, supabaseKey.trim())
            putExtra(VoiceService.EXTRA_ROOM_CODE, code)
            putExtra(VoiceService.EXTRA_NAME, voiceName.trim())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendVoiceAction(action: String) {
        startService(Intent(this, VoiceService::class.java).apply {
            this.action = action
        })
    }

    private fun startHudService() {
        startService(Intent(this, HudService::class.java).apply {
            action = HudService.ACTION_START_HUD
        })
    }

    private fun stopHudService() {
        startService(Intent(this, HudService::class.java).apply {
            action = HudService.ACTION_STOP_HUD
        })
    }

    @Composable
    private fun SquadStreamScreen() {
        val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
        val status by StreamState.status.collectAsStateWithLifecycle()
        val measured by StreamState.measuredBitrateBps.collectAsStateWithLifecycle()
        val encoderBps by StreamState.encoderBitrateBps.collectAsStateWithLifecycle()
        val logLines by StreamState.log.collectAsStateWithLifecycle()
        val voiceConnected by StreamState.voiceConnected.collectAsStateWithLifecycle()
        val voiceCode by StreamState.voiceRoomCode.collectAsStateWithLifecycle()
        val voicePeers by StreamState.voicePeers.collectAsStateWithLifecycle()
        val voiceMicMuted by StreamState.voiceMicMuted.collectAsStateWithLifecycle()
        val voiceSpeakerOn by StreamState.voiceSpeakerOn.collectAsStateWithLifecycle()

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
                        onClick = {
                            audioMode = mode
                            // The radio is a preset for the live mix gains
                            // (the HUD sliders move the same values).
                            when (mode) {
                                AudioMode.MIX -> {
                                    StreamState.setMicGain(100)
                                    StreamState.setGameGain(100)
                                }
                                AudioMode.INTERNAL_ONLY -> {
                                    StreamState.setMicGain(0)
                                    StreamState.setGameGain(100)
                                }
                                AudioMode.MIC_ONLY -> {
                                    StreamState.setMicGain(100)
                                    StreamState.setGameGain(0)
                                }
                            }
                        },
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

            Text("Floating controls (in-game bubble)", style = MaterialTheme.typography.titleMedium)
            Text(
                "A small bubble floats over your game: stop/start, mic mute, " +
                    "mic/game mix and live bitrate — without leaving the game.",
                style = MaterialTheme.typography.bodySmall
            )
            if (!overlayGranted) {
                Text(
                    "Needs the 'Display over other apps' permission.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow display over other apps")
                }
            } else if (!hudEnabled) {
                Button(
                    onClick = {
                        prefs.edit().putBoolean(HudService.PREF_HUD_ENABLED, true).apply()
                        hudEnabled = true
                        startHudService()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable floating controls")
                }
            } else {
                Button(
                    onClick = {
                        prefs.edit().putBoolean(HudService.PREF_HUD_ENABLED, false).apply()
                        hudEnabled = false
                        stopHudService()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable floating controls")
                }
            }

            Text("Squad voice (communication room)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Talk with your squad while you play. Needs a free Supabase project first (one-time setup — see the README).",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = supabaseUrl,
                onValueChange = { supabaseUrl = it; saveVoicePrefs() },
                label = { Text("Supabase URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = supabaseKey,
                onValueChange = { supabaseKey = it; saveVoicePrefs() },
                label = { Text("Supabase anon key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            OutlinedTextField(
                value = voiceName,
                onValueChange = { voiceName = it; saveVoicePrefs() },
                label = { Text("Your name in the room") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (!voiceConnected) {
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase() },
                    label = { Text("Room code (6 characters)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCreateRoomTapped() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Create room") }
                    Button(
                        onClick = { onJoinRoomTapped() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Join room") }
                }
            } else {
                Text(
                    "Room $voiceCode • ${voicePeers.size} mate${if (voicePeers.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (voicePeers.isEmpty()) {
                    Text(
                        "Waiting for squad mates… share the code: $voiceCode",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                voicePeers.forEach { peer ->
                    Text("• $peer", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sendVoiceAction(VoiceService.ACTION_TOGGLE_MIC) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (voiceMicMuted) "Unmute my mic" else "Mute my mic") }
                    Button(
                        onClick = { sendVoiceAction(VoiceService.ACTION_TOGGLE_SPEAKER) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (voiceSpeakerOn) "Speaker on" else "Speaker off") }
                }
                Button(
                    onClick = { sendVoiceAction(VoiceService.ACTION_LEAVE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("LEAVE ROOM") }
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
