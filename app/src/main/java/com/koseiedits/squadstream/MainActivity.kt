package com.koseiedits.squadstream

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koseiedits.squadstream.ui.screens.LobbyScreen
import com.koseiedits.squadstream.ui.screens.VoiceRoomScreen
import com.koseiedits.squadstream.ui.sheets.AudioMixerSheet
import com.koseiedits.squadstream.ui.sheets.StreamSettingsSheet
import com.koseiedits.squadstream.ui.theme.DiscordBackground
import com.koseiedits.squadstream.ui.theme.SquadStreamTheme

/**
 * Main screen — Discord-style lobby + voice room (see the UI checklist).
 *
 * All the logic lives here (permissions, capture consent, service intents,
 * voice join, HUD toggle); the look lives in ui/screens + ui/sheets.
 * The audio-mode radios are gone: the mix is now the Game / Mic gains
 * (mixer sheet + HUD sliders) and the mode is derived from them at
 * stream start.
 */
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

    // Phase 2 — floating controls state.
    private var hudEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)

    // Phase 3 — squad voice room settings. The Ably API key lives in
    // SharedPreferences only — never in code, never in git.
    private var ablyKey by mutableStateOf("")
    private var voiceName by mutableStateOf("")
    private var joinCode by mutableStateOf("")
    private var pendingVoiceCode: String? = null

    // UI state (Discord layout): which sheet is open, and whether the
    // voice room is minimised to the lobby.
    private var showSettingsSheet by mutableStateOf(false)
    private var showMixerSheet by mutableStateOf(false)
    private var showNameDialog by mutableStateOf(false)
    private var roomMinimized by mutableStateOf(false)

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
        // Edge-to-edge with dark system bars; the root layout adds
        // statusBarsPadding() so the header never sits under the clock.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        refreshHudState()
        ablyKey = prefs.getString("ably_key", "").orEmpty()
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
     * stream at once when a key is already pasted; otherwise open Settings
     * so the user can paste the key first.
     */
    private fun handleAutoStartIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_CAPTURE, false) != true) return
        intent.removeExtra(EXTRA_AUTO_START_CAPTURE)
        if (streamKey.isBlank()) {
            showSettingsSheet = true
            toast("Paste your stream key first, then tap Go Live")
            return
        }
        onStartTapped()
    }

    private fun refreshHudState() {
        hudEnabled = prefs.getBoolean(HudService.PREF_HUD_ENABLED, false)
        overlayGranted = Settings.canDrawOverlays(this)
    }

    // ---- Phase 3: squad voice room ----

    // The key the app actually uses: whatever was pasted in the field,
    // otherwise the key baked in at build time (GitHub secret ABLY_API_KEY).
    // The paste field is an override — leave it blank to use the built-in key.
    private fun effectiveAblyKey(): String =
        ablyKey.trim().ifBlank { BuildConfig.ABLY_API_KEY }

    private fun saveVoicePrefs() {
        prefs.edit()
            .putString("ably_key", ablyKey.trim())
            .putString("voice_name", voiceName.trim())
            .apply()
    }

    private fun onCreateRoomTapped() {
        if (voiceName.isBlank()) {
            showNameDialog = true
            toast("Type your name first")
            return
        }
        val code = RoomCodes.generate()
        joinCode = code
        startVoiceJoin(code)
    }

    private fun onJoinRoomTapped() {
        if (voiceName.isBlank()) {
            showNameDialog = true
            toast("Type your name first")
            return
        }
        val code = joinCode.trim().uppercase()
        if (!RoomCodes.isValid(code)) {
            toast("Room code is 6 characters, A–Z and 2–9")
            return
        }
        startVoiceJoin(code)
    }

    private fun startVoiceJoin(code: String) {
        if (effectiveAblyKey().isBlank()) {
            toast("Paste the Ably API key first")
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
            putExtra(VoiceService.EXTRA_ABLY_KEY, effectiveAblyKey())
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

    private fun sendStreamGain(action: String, gain: Int) {
        startService(Intent(this, StreamService::class.java).apply {
            this.action = action
            putExtra(StreamService.EXTRA_GAIN, gain)
        })
    }

    private fun sendSquadMuteToggle() {
        startService(Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_TOGGLE_SQUAD_MUTE
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

    private fun toggleHud(on: Boolean) {
        prefs.edit().putBoolean(HudService.PREF_HUD_ENABLED, on).apply()
        hudEnabled = on
        if (on) startHudService() else stopHudService()
    }

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** The stream's audio mode, derived from the live mix gains. */
    private fun deriveAudioMode(): AudioMode = when {
        StreamState.micGain.value > 0 && StreamState.gameGain.value > 0 -> AudioMode.MIX
        StreamState.micGain.value == 0 -> AudioMode.INTERNAL_ONLY
        else -> AudioMode.MIC_ONLY
    }

    /** Go Live (dock / lobby): needs a stream key, else open Settings. */
    private fun onGoLiveTapped() {
        if (StreamState.isStreaming.value) {
            onStopTapped()
            return
        }
        if (streamKey.isBlank()) {
            showSettingsSheet = true
            toast("Paste your YouTube stream key first")
            return
        }
        onStartTapped()
    }

    /** Leave: leave the room if in one, else stop the solo stream. */
    private fun onLeaveTapped() {
        if (StreamState.voiceConnected.value) {
            sendVoiceAction(VoiceService.ACTION_LEAVE)
        } else if (StreamState.isStreaming.value) {
            onStopTapped()
        }
        roomMinimized = false
    }

    private fun copyRoomCode() {
        val code = StreamState.voiceRoomCode.value
        if (code.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SquadStream room", code))
        toast("Room code copied: $code")
    }

    private fun shareRoomCode() {
        val code = StreamState.voiceRoomCode.value
        if (code.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my SquadStream room: $code")
        }
        startActivity(Intent.createChooser(intent, "Invite squad"))
    }

    @Composable
    private fun SquadStreamScreen() {
        val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()
        val voiceConnected by StreamState.voiceConnected.collectAsStateWithLifecycle()

        // Joining opens the room; leaving/stopping clears the minimise flag.
        LaunchedEffect(voiceConnected) {
            if (voiceConnected) roomMinimized = false
        }

        SquadStreamTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DiscordBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                if ((voiceConnected || isStreaming) && !roomMinimized) {
                    VoiceRoomScreen(
                        myName = voiceName,
                        presetLabel = "${selectedPreset.height}p${selectedPreset.fps}",
                        onMinimize = { roomMinimized = true },
                        onCopyCode = { copyRoomCode() },
                        onShareCode = { shareRoomCode() },
                        onToggleSpeaker = { sendVoiceAction(VoiceService.ACTION_TOGGLE_SPEAKER) },
                        onOpenSettings = { showSettingsSheet = true },
                        onToggleMic = { sendVoiceAction(VoiceService.ACTION_TOGGLE_MIC) },
                        onGoLive = { onGoLiveTapped() },
                        onOpenMixer = { showMixerSheet = true },
                        onLeave = { onLeaveTapped() },
                    )
                } else {
                    LobbyScreen(
                        voiceName = voiceName,
                        onEditName = { showNameDialog = true },
                        joinCode = joinCode,
                        onJoinCodeChange = { joinCode = it },
                        onCreateRoom = { onCreateRoomTapped() },
                        onJoinRoom = { onJoinRoomTapped() },
                        onGoLiveSolo = { onGoLiveTapped() },
                        streamKeySet = streamKey.isNotBlank(),
                        presetLabel = "${selectedPreset.height}p${selectedPreset.fps}",
                        hudEnabled = hudEnabled,
                        onOpenSettings = { showSettingsSheet = true },
                        onBackToRoom = { roomMinimized = false },
                    )
                }

                if (showSettingsSheet) {
                    StreamSettingsSheet(
                        rtmpUrl = rtmpUrl,
                        onRtmpUrlChange = { rtmpUrl = it },
                        streamKey = streamKey,
                        onStreamKeyChange = { streamKey = it },
                        isStreaming = isStreaming,
                        presets = StreamConfig.PRESETS,
                        selectedPreset = selectedPreset,
                        onPresetChange = { selectedPreset = it },
                        hudEnabled = hudEnabled,
                        overlayGranted = overlayGranted,
                        onRequestOverlay = { requestOverlayPermission() },
                        onToggleHud = { toggleHud(it) },
                        ablyKey = ablyKey,
                        onAblyKeyChange = { ablyKey = it; saveVoicePrefs() },
                        onClearAblyKey = {
                            ablyKey = ""
                            prefs.edit().remove("ably_key").apply()
                            toast("Saved key cleared — the built-in key will be used")
                        },
                        builtInKeyPresent = BuildConfig.ABLY_API_KEY.isNotBlank(),
                        onDismiss = { showSettingsSheet = false },
                    )
                }

                if (showMixerSheet) {
                    AudioMixerSheet(
                        onMicGainChange = { sendStreamGain(StreamService.ACTION_SET_MIC_GAIN, it) },
                        onGameGainChange = { sendStreamGain(StreamService.ACTION_SET_GAME_GAIN, it) },
                        onToggleSquadMute = { sendSquadMuteToggle() },
                        onDismiss = { showMixerSheet = false },
                    )
                }

                if (showNameDialog) {
                    AlertDialog(
                        onDismissRequest = { showNameDialog = false },
                        title = { Text("Your name in the room") },
                        text = {
                            OutlinedTextField(
                                value = voiceName,
                                onValueChange = { voiceName = it; saveVoicePrefs() },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showNameDialog = false }) { Text("Done") }
                        }
                    )
                }
            }
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
            putExtra(StreamService.EXTRA_AUDIO_MODE, deriveAudioMode().name)
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
