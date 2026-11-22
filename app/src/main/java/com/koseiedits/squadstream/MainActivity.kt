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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koseiedits.squadstream.ui.screens.LobbyScreen
import com.koseiedits.squadstream.ui.sheets.AudioMixerSheet
import com.koseiedits.squadstream.ui.sheets.StreamSettingsSheet
import com.koseiedits.squadstream.ui.theme.DiscordBackground
import com.koseiedits.squadstream.ui.theme.SquadStreamTheme

/**
 * Main screen — streaming-only lobby.
 *
 * The squad voice room was removed on 2026-11-22: voice over mobile data
 * needs a server-side relay, and every free public relay we tried was
 * dead or unverified (OpenRelay's public login, its shared-secret scheme).
 * The code is still in git history if voice ever comes back via a real
 * service (Agora). What remains is the part that works: screen + game
 * audio + mic streamed to YouTube over RTMP.
 *
 * All the logic lives here (permissions, capture consent, service intents,
 * HUD toggle); the look lives in ui/screens + ui/sheets.
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

    // UI state: which sheet is open.
    private var showSettingsSheet by mutableStateOf(false)
    private var showMixerSheet by mutableStateOf(false)

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

    private fun sendStreamGain(action: String, gain: Int) {
        startService(Intent(this, StreamService::class.java).apply {
            this.action = action
            putExtra(StreamService.EXTRA_GAIN, gain)
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

    /** Go Live: needs a stream key, else open Settings. Stops if already live. */
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

    @Composable
    private fun SquadStreamScreen() {
        val isStreaming by StreamState.isStreaming.collectAsStateWithLifecycle()

        SquadStreamTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DiscordBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                LobbyScreen(
                    onGoLive = { onGoLiveTapped() },
                    onOpenMixer = { showMixerSheet = true },
                    streamKeySet = streamKey.isNotBlank(),
                    presetLabel = "${selectedPreset.height}p${selectedPreset.fps}",
                    hudEnabled = hudEnabled,
                    onOpenSettings = { showSettingsSheet = true },
                )

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
                        onDismiss = { showSettingsSheet = false },
                    )
                }

                if (showMixerSheet) {
                    AudioMixerSheet(
                        onMicGainChange = { sendStreamGain(StreamService.ACTION_SET_MIC_GAIN, it) },
                        onGameGainChange = { sendStreamGain(StreamService.ACTION_SET_GAME_GAIN, it) },
                        onDismiss = { showMixerSheet = false },
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
