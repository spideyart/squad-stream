package com.koseiedits.squadstream

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.AndroidViewFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * Phase 1 — foreground service that captures the screen + game audio + mic
 * and pushes H.264 + AAC to YouTube over RTMP (RootEncoder GenericStream).
 *
 * Flow (Android 14+ rules — order matters):
 *   1. MainActivity gets the user's screen-capture consent via
 *      MediaProjectionManager.createScreenCaptureIntent().
 *   2. The result (resultCode + data) is handed to this service.
 *   3. Service calls startForeground() with types mediaProjection|microphone.
 *   4. ONLY THEN MediaProjectionManager.getMediaProjection() is called.
 *      Calling it before startForeground() throws SecurityException on API 34+.
 *   5. Encoders are prepared, screen + audio sources are swapped in,
 *      and the RTMP stream starts.
 *
 * Audio (Phase 1): RootEncoder's built-in MixAudioSource mixes the mic
 * (VOICE_COMMUNICATION + hardware echo canceler + noise suppressor) with
 * internal game audio (AudioPlaybackCapture, USAGE_GAME + USAGE_MEDIA).
 * The custom 3-channel PCM mixer in Phase 4 adds squad voices as a third
 * source — the library cannot mix WebRTC audio, so that part is ours.
 *
 * Audio (Phase 2): the HUD mix sliders drive applyAudioMix(), which swaps
 * the audio source at runtime (changeAudioSource is safe while streaming):
 * Mix ↔ Mic-only ↔ Game-only ↔ silence. The library has no per-source
 * gain, so a slider at 0 switches its source off; true per-channel gain
 * (0–100 volume, not just on/off) arrives with the Phase 4 mixer.
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamService"
        const val CHANNEL_ID = "squadstream_live"
        const val NOTIFY_ID = 1001

        const val ACTION_START = "com.koseiedits.squadstream.ACTION_START"
        const val ACTION_STOP = "com.koseiedits.squadstream.ACTION_STOP"

        // Phase 2 — HUD commands.
        const val ACTION_SET_MIC_GAIN = "com.koseiedits.squadstream.ACTION_SET_MIC_GAIN"
        const val ACTION_SET_GAME_GAIN = "com.koseiedits.squadstream.ACTION_SET_GAME_GAIN"
        const val ACTION_TOGGLE_SQUAD_MUTE =
            "com.koseiedits.squadstream.ACTION_TOGGLE_SQUAD_MUTE"

        const val EXTRA_GAIN = "gain"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RTMP_URL = "rtmp_url"
        const val EXTRA_STREAM_KEY = "stream_key"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_VIDEO_BITRATE = "video_bitrate"
        const val EXTRA_AUDIO_MODE = "audio_mode"

        var INSTANCE: StreamService? = null
            private set
    }

    private lateinit var genericStream: GenericStream
    private var mediaProjection: MediaProjection? = null
    private var prepared = false
    private var config: StreamConfig? = null
    private var currentEncoderBitrate = 0
    private var lastAdaptMs = 0L
    private var lowBitrateStreak = 0
    private var currentAudioKind = AudioKind.MIX

    private val mediaProjectionManager by lazy {
        applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    /** If the user revokes capture from the system UI, stop the stream cleanly. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system/user")
            StreamState.addLog("Screen capture stopped")
            handleStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        INSTANCE = this
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_SET_MIC_GAIN -> handleSetMicGain(intent)
            ACTION_SET_GAME_GAIN -> handleSetGameGain(intent)
            ACTION_TOGGLE_SQUAD_MUTE -> handleToggleSquadMute()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- start / stop ----

    private fun handleStart(intent: Intent) {
        if (::genericStream.isInitialized && genericStream.isStreaming) {
            Log.i(TAG, "Already streaming, ignoring start")
            return
        }
        val cfg = StreamConfig(
            width = intent.getIntExtra(EXTRA_WIDTH, 1280),
            height = intent.getIntExtra(EXTRA_HEIGHT, 720),
            fps = intent.getIntExtra(EXTRA_FPS, 30),
            videoBitrateBps = intent.getIntExtra(EXTRA_VIDEO_BITRATE, 4_000_000),
            audioMode = runCatching {
                AudioMode.valueOf(intent.getStringExtra(EXTRA_AUDIO_MODE) ?: "MIX")
            }.getOrDefault(AudioMode.MIX),
            rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL)
                ?: "rtmp://a.rtmp.youtube.com/live2",
            streamKey = intent.getStringExtra(EXTRA_STREAM_KEY).orEmpty()
        )
        if (cfg.streamKey.isBlank()) {
            StreamState.setStatus("Missing stream key")
            StreamState.addLog("Start failed: stream key is empty")
            stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            StreamState.setStatus("Screen capture denied")
            StreamState.addLog("Start failed: no capture consent")
            stopSelf()
            return
        }

        config = cfg
        currentEncoderBitrate = cfg.videoBitrateBps
        lowBitrateStreak = 0
        lastAdaptMs = 0L

        // Android 14+: startForeground with mediaProjection|microphone MUST run
        // BEFORE getMediaProjection() is called.
        startForeground(
            NOTIFY_ID,
            buildNotification("Starting stream…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        StreamState.setStatus("Starting…")

        if (!prepareStream(resultCode, resultData, cfg)) {
            StreamState.setStatus("Prepare failed")
            handleStop()
            return
        }
        genericStream.startStream(cfg.fullUrl)
        StreamState.setStreaming(true)
        StreamState.setStartedAt(SystemClock.elapsedRealtime())
        StreamState.setStatus("Live • ${cfg.label}")
        StreamState.setEncoderBitrate(cfg.videoBitrateBps)
        StreamState.addLog("Streaming to YouTube (${cfg.label}, ${cfg.audioMode})")
        notificationManager.notify(NOTIFY_ID, buildNotification("LIVE • ${cfg.label}"))
    }

    /**
     * Creates the MediaProjection (only legal after startForeground on Android 14+),
     * prepares encoders, swaps in the screen + audio sources. Returns false on failure.
     */
    private fun prepareStream(resultCode: Int, data: Intent, cfg: StreamConfig): Boolean {
        return try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                ?: return false
            mediaProjection?.registerCallback(
                projectionCallback, Handler(Looper.getMainLooper())
            )

            if (!prepared) {
                genericStream = GenericStream(
                    baseContext, this, NoVideoSource(), MicrophoneSource()
                ).apply {
                    // MediaProjection only produces frames when the screen changes;
                    // force a steady 15 fps floor so the encoder never starves.
                    getGlInterface().setForceRender(true, 15)
                }
                val videoOk = genericStream.prepareVideo(
                    cfg.width, cfg.height, cfg.videoBitrateBps,
                    fps = cfg.fps, rotation = 0
                )
                val audioOk = genericStream.prepareAudio(
                    44100, true, 128 * 1000,
                    echoCanceler = true, noiseSuppressor = true
                )
                if (!videoOk || !audioOk) {
                    Log.e(TAG, "prepareVideo=$videoOk prepareAudio=$audioOk")
                    return false
                }
                prepared = true
            }

            val projection = mediaProjection ?: return false
            genericStream.changeVideoSource(ScreenSource(applicationContext, projection))
            // Phase 2: the initial audio source comes from the live mix gains
            // (MainActivity's audio-mode radio writes them before Start).
            currentAudioKind = audioKindForGains()
            genericStream.changeAudioSource(audioSourceForGains(projection))
            applyLiveBadgeOverlay(cfg)
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareStream failed", e)
            StreamState.addLog("Prepare failed: ${e.message}")
            false
        }
    }

    /**
     * Best-effort "LIVE" badge drawn over the stream. Never breaks streaming.
     *
     * Sized relative to the stream frame, not the phone screen: the old
     * 20sp badge was drawn in phone pixels, so on a 720p stream it covered
     * a third of the picture. Now it is a small rounded "● LIVE" pill,
     * about 2% of the frame width, top-left.
     */
    private fun applyLiveBadgeOverlay(cfg: StreamConfig) {
        try {
            val textPx = cfg.width * 0.02f
            val padH = (cfg.width * 0.012f).toInt()
            val padV = (cfg.width * 0.006f).toInt()
            val margin = (cfg.width * 0.015f).toInt()
            val badge = TextView(this).apply {
                text = "● LIVE"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                setTypeface(Typeface.DEFAULT_BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6C62828"))
                    cornerRadius = textPx
                }
                setPadding(padH, padV, padH, padV)
            }
            val frame = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(
                    badge,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(margin, margin, 0, 0) }
                )
            }
            // The filter view must be measured and laid out by hand:
            // it is never attached to a window.
            frame.measure(
                View.MeasureSpec.makeMeasureSpec(cfg.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cfg.height, View.MeasureSpec.EXACTLY)
            )
            frame.layout(0, 0, cfg.width, cfg.height)
            val filter = AndroidViewFilterRender().apply { view = frame }
            genericStream.getGlInterface().setFilter(filter)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay failed (stream continues without badge)", e)
        }
    }

    private fun handleStop() {
        try {
            if (::genericStream.isInitialized && genericStream.isStreaming) {
                genericStream.stopStream()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopStream failed", e)
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        mediaProjection?.stop()
        mediaProjection = null
        if (::genericStream.isInitialized) {
            try {
                genericStream.release()
            } catch (_: Exception) {
            }
        }
        prepared = false
        config = null
        currentAudioKind = AudioKind.MIX
        StreamState.reset()
        StreamState.addLog("Stream stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::genericStream.isInitialized && genericStream.isStreaming) {
            try {
                genericStream.stopStream()
            } catch (_: Exception) {
            }
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        mediaProjection?.stop()
        mediaProjection = null
        if (::genericStream.isInitialized) {
            try {
                genericStream.release()
            } catch (_: Exception) {
            }
        }
        INSTANCE = null
        Log.i(TAG, "Service destroyed")
    }

    // ---- notification ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SquadStream Live", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while SquadStream is live on YouTube" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SquadStream")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ---- Phase 2: live audio mix (HUD) ----

    private fun audioKindForGains(): AudioKind {
        val micOn = StreamState.micGain.value > 0
        val gameOn = StreamState.gameGain.value > 0
        return when {
            micOn && gameOn -> AudioKind.MIX
            micOn -> AudioKind.MIC_ONLY
            gameOn -> AudioKind.GAME_ONLY
            else -> AudioKind.NONE
        }
    }

    private fun audioSourceForGains(projection: MediaProjection): AudioSource {
        return when (audioKindForGains()) {
            AudioKind.MIX -> MixAudioSource(projection)
            AudioKind.MIC_ONLY -> MicrophoneSource()
            AudioKind.GAME_ONLY -> InternalAudioSource(projection)
            AudioKind.NONE -> NoAudioSource()
        }
    }

    /**
     * Swaps the audio source only when the effective mix actually changed,
     * so dragging a slider doesn't recreate sources on every tick.
     */
    private fun applyAudioMix() {
        val kind = audioKindForGains()
        if (kind == currentAudioKind) return
        currentAudioKind = kind
        if (!::genericStream.isInitialized || !genericStream.isStreaming) return
        val projection = mediaProjection ?: return
        try {
            genericStream.changeAudioSource(audioSourceForGains(projection))
            StreamState.addLog("Audio mix → $kind")
        } catch (e: Exception) {
            Log.w(TAG, "Audio mix switch failed", e)
            StreamState.addLog("Audio mix failed: ${e.message}")
        }
    }

    private fun handleSetMicGain(intent: Intent) {
        StreamState.setMicGain(intent.getIntExtra(EXTRA_GAIN, 100))
        applyAudioMix()
        if (!StreamState.isStreaming.value) stopSelf()
    }

    private fun handleSetGameGain(intent: Intent) {
        StreamState.setGameGain(intent.getIntExtra(EXTRA_GAIN, 100))
        applyAudioMix()
        if (!StreamState.isStreaming.value) stopSelf()
    }

    private fun handleToggleSquadMute() {
        val muted = !StreamState.squadMuted.value
        StreamState.setSquadMuted(muted)
        StreamState.addLog(
            if (muted) "Squad muted (takes effect in Phase 3)"
            else "Squad unmuted"
        )
        if (!StreamState.isStreaming.value) stopSelf()
    }

    // ---- adaptive bitrate (simple, conservative) ----

    private fun applyBitrate(bps: Int) {
        if (!::genericStream.isInitialized || !genericStream.isStreaming) return
        genericStream.setVideoBitrateOnFly(bps)
        currentEncoderBitrate = bps
        StreamState.setEncoderBitrate(bps)
        StreamState.addLog("Bitrate adapted → ${bps / 1000} kbps")
    }

    // ---- ConnectChecker (RootEncoder callbacks) ----

    override fun onConnectionStarted(url: String) {
        StreamState.setStatus("Connecting…")
        StreamState.addLog("Connecting to RTMP server")
    }

    override fun onConnectionSuccess() {
        StreamState.setStatus("Live • ${config?.label.orEmpty()}")
        StreamState.addLog("Connected — you are LIVE")
    }

    override fun onConnectionFailed(reason: String) {
        StreamState.setStatus("Failed: $reason")
        StreamState.addLog("Connection failed: $reason")
        handleStop()
    }

    override fun onNewBitrate(bitrate: Long) {
        StreamState.setMeasuredBitrate(bitrate)
        val target = config?.videoBitrateBps ?: return
        if (!::genericStream.isInitialized || !genericStream.isStreaming || bitrate <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptMs < 10_000) return
        when {
            bitrate < currentEncoderBitrate * 0.7 -> {
                // Two bad readings in a row before stepping down (avoids flapping).
                if (++lowBitrateStreak >= 2) {
                    applyBitrate(maxOf(800_000, (currentEncoderBitrate * 0.7).toInt()))
                    lowBitrateStreak = 0
                    lastAdaptMs = now
                }
            }
            bitrate > target * 1.2 && currentEncoderBitrate < target -> {
                applyBitrate(minOf(target, (currentEncoderBitrate * 1.3).toInt()))
                lastAdaptMs = now
            }
            else -> lowBitrateStreak = 0
        }
    }

    override fun onDisconnect() {
        StreamState.addLog("Disconnected")
        handleStop()
    }

    override fun onAuthError() {
        StreamState.setStatus("Auth error — check stream key")
        StreamState.addLog("Auth error: wrong stream key?")
        handleStop()
    }

    override fun onAuthSuccess() {
        StreamState.addLog("RTMP auth OK")
    }
}

/** Effective audio mix, derived from the HUD's mic/game gains. */
private enum class AudioKind {
    MIX, MIC_ONLY, GAME_ONLY, NONE
}
