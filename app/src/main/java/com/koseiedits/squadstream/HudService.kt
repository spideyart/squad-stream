package com.koseiedits.squadstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 2 — floating overlay HUD: a draggable bubble that floats over the
 * game, with an expandable control panel (stop/start, mic mute, mix
 * sliders, live bitrate). No need to leave the game to control the stream.
 *
 * How it survives: a foreground service (type `specialUse` on Android 14+ —
 * the bubble fits no other foreground-service type; the subtype property
 * is declared in the manifest). On older Android it runs as a plain
 * foreground service. The overlay itself needs SYSTEM_ALERT_WINDOW
 * ("Display over other apps"), granted by the user in Settings; without
 * it this service refuses to start.
 *
 * The bubble only appears when the user enables floating controls in
 * MainActivity. It stays on screen whether streaming or idle: idle shows
 * START (opens the app to run the capture-consent flow), live shows STOP
 * plus the mix controls.
 */
class HudService : Service() {

    companion object {
        private const val TAG = "HudService"
        const val CHANNEL_ID = "squadstream_hud"
        const val NOTIFY_ID = 1002

        const val ACTION_START_HUD = "com.koseiedits.squadstream.ACTION_START_HUD"
        const val ACTION_STOP_HUD = "com.koseiedits.squadstream.ACTION_STOP_HUD"

        const val PREFS = "squadstream"
        const val PREF_HUD_ENABLED = "hud_enabled"
        private const val PREF_BUBBLE_X = "hud_bubble_x"
        private const val PREF_BUBBLE_Y = "hud_bubble_y"

        private const val PANEL_WIDTH_DP = 300
        private const val BUBBLE_MIN_HEIGHT_DP = 52
        private const val DRAG_THRESHOLD_DP = 12
    }

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var bubble: FrameLayout? = null
    private var bubbleText: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: ScrollView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private var observing = false

    private var statusText: TextView? = null
    private var elapsedText: TextView? = null
    private var bitrateText: TextView? = null
    private var micButton: Button? = null
    private var squadButton: Button? = null
    private var micSeek: SeekBar? = null
    private var gameSeek: SeekBar? = null
    private var startStopButton: Button? = null

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private var tickRunning = false
    private val elapsedTick = object : Runnable {
        override fun run() {
            updateElapsed()
            elapsedHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_HUD -> {
                if (!Settings.canDrawOverlays(this)) {
                    StreamState.addLog("Floating controls need 'Display over other apps'")
                    Log.w(TAG, "No overlay permission, refusing to start HUD")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundService()
                showBubble()
                observeState()
            }
            ACTION_STOP_HUD -> {
                teardown()
                stopSelf()
            }
            else -> {
                // Restarted by the system with a null intent: only stay up if
                // the user still wants the bubble and the permission is there.
                if (prefs.getBoolean(PREF_HUD_ENABLED, false) &&
                    Settings.canDrawOverlays(this)
                ) {
                    startForegroundService()
                    showBubble()
                    observeState()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- foreground plumbing ----

    private fun startForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFY_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SquadStream Controls", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the floating stream controls alive" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val hide = PendingIntent.getService(
            this, 1,
            Intent(this, HudService::class.java).apply { action = ACTION_STOP_HUD },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SquadStream controls")
            .setContentText("Floating controls active — tap to open SquadStream")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide", hide)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ---- bubble ----

    private fun showBubble() {
        if (bubble != null) return
        val metrics = resources.displayMetrics
        val startX = prefs.getInt(PREF_BUBBLE_X, dp(24))
        val startY = prefs.getInt(PREF_BUBBLE_Y, metrics.heightPixels / 3)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bubbleText = text
        val pill = FrameLayout(this).apply {
            background = roundedBg(Color.parseColor("#1E1F22"), dp(26))
            minimumHeight = dp(BUBBLE_MIN_HEIGHT_DP)
            addView(text)
        }
        pill.setOnTouchListener(BubbleTouchListener())
        bubble = pill
        windowManager.addView(pill, bubbleParams)
        refreshBubble()
    }

    /** Drag with a finger, tap to open/close the panel. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(DRAG_THRESHOLD_DP) ||
                        kotlin.math.abs(dy) > dp(DRAG_THRESHOLD_DP)
                    ) {
                        if (!moved && panelVisible) hidePanel()
                        moved = true
                    }
                    if (moved) {
                        params.x = downParamX + dx
                        params.y = downParamY + dy
                        windowManager.updateViewLayout(bubble, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit()
                            .putInt(PREF_BUBBLE_X, params.x)
                            .putInt(PREF_BUBBLE_Y, params.y)
                            .apply()
                    } else {
                        togglePanel()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun refreshBubble() {
        val live = StreamState.isStreaming.value
        bubbleText?.text = if (live) "● LIVE" else "SquadStream"
        bubble?.background = roundedBg(
            if (live) Color.parseColor("#DA373C") else Color.parseColor("#1E1F22"),
            dp(26)
        )
    }

    // ---- panel ----

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panel != null) {
            panelVisible = true
            return
        }
        val metrics = resources.displayMetrics
        panelParams = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.parseColor("#E61E1F22"), dp(14))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        elapsedText = TextView(this).apply {
            setTextColor(Color.parseColor("#B5BAC1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        bitrateText = TextView(this).apply {
            setTextColor(Color.parseColor("#B5BAC1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        micButton = makeButton("Mic: ON") { onMicToggle() }
        squadButton = makeButton("Squad: ON") { onSquadToggle() }
        micSeek = makeSeekBar { gain -> sendGain(StreamService.ACTION_SET_MIC_GAIN, gain) }
        gameSeek = makeSeekBar { gain -> sendGain(StreamService.ACTION_SET_GAME_GAIN, gain) }
        startStopButton = makeButton("START STREAM") { onStartStop() }
        val openAppButton = makeButton("Open app") { openApp(autoStart = false) }
        val hideButton = makeButton("Hide panel") { hidePanel() }

        content.addView(statusText)
        content.addView(elapsedText)
        content.addView(bitrateText)
        content.addView(divider())
        content.addView(micButton)
        content.addView(squadButton)
        content.addView(label("Mic volume"))
        content.addView(micSeek)
        content.addView(label("Game volume"))
        content.addView(gameSeek)
        content.addView(startStopButton)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(openAppButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(hideButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)

        val scroll = ScrollView(this).apply { addView(content) }
        panel = scroll
        positionPanel()
        windowManager.addView(scroll, panelParams)
        panelVisible = true
        // Cap the panel at 85% of screen height (games run landscape).
        scroll.post {
            val maxH = (metrics.heightPixels * 0.85).toInt()
            if (content.height > maxH) {
                panelParams?.height = maxH
                panelParams?.let { windowManager.updateViewLayout(scroll, it) }
            }
        }
        refreshPanel()
    }

    private fun positionPanel() {
        val params = panelParams ?: return
        val bubbleP = bubbleParams ?: return
        val metrics = resources.displayMetrics
        val bubbleH = bubble?.height ?: dp(BUBBLE_MIN_HEIGHT_DP)
        var x = bubbleP.x
        var y = bubbleP.y + bubbleH + dp(8)
        val panelW = dp(PANEL_WIDTH_DP)
        if (x + panelW > metrics.widthPixels) x = metrics.widthPixels - panelW - dp(8)
        if (x < 0) x = 0
        // Not enough room below the bubble: open above it instead.
        if (y + dp(200) > metrics.heightPixels) y = bubbleP.y - dp(200)
        if (y < 0) y = 0
        params.x = x
        params.y = y
    }

    private fun hidePanel() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        panelParams = null
        panelVisible = false
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBg(Color.parseColor("#2B2D31"), dp(8))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#B5BAC1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#2B2D31"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
    }

    private fun makeSeekBar(onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }
                override fun onStartTrackingTouch(seek: SeekBar) {}
                override fun onStopTrackingTouch(seek: SeekBar) {}
            })
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
    }

    // ---- panel actions ----

    private fun onMicToggle() {
        val gain = if (StreamState.micGain.value > 0) 0 else 100
        sendGain(StreamService.ACTION_SET_MIC_GAIN, gain)
    }

    /** Squad mute = stop hearing the squad (my mic to them is untouched). */
    private fun onSquadToggle() {
        startService(Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_TOGGLE_SQUAD_MUTE
        })
    }

    private fun onStartStop() {
        if (StreamState.isStreaming.value) {
            startService(Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_STOP
            })
        } else {
            openApp(autoStart = true)
        }
    }

    private fun sendGain(action: String, gain: Int) {
        startService(Intent(this, StreamService::class.java).apply {
            this.action = action
            putExtra(StreamService.EXTRA_GAIN, gain)
        })
    }

    private fun openApp(autoStart: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_AUTO_START_CAPTURE, autoStart)
        }
        startActivity(intent)
    }

    // ---- live updates ----

    private fun observeState() {
        if (observing) return
        observing = true
        serviceScope.launch { StreamState.isStreaming.collect { refreshAll() } }
        serviceScope.launch { StreamState.status.collect { refreshAll() } }
        serviceScope.launch { StreamState.measuredBitrateBps.collect { refreshAll() } }
        serviceScope.launch { StreamState.encoderBitrateBps.collect { refreshAll() } }
        serviceScope.launch { StreamState.micGain.collect { refreshAll() } }
        serviceScope.launch { StreamState.gameGain.collect { refreshAll() } }
        serviceScope.launch { StreamState.squadMuted.collect { refreshAll() } }
        if (!tickRunning) {
            tickRunning = true
            elapsedHandler.post(elapsedTick)
        }
    }

    private fun refreshAll() {
        refreshBubble()
        refreshPanel()
    }

    private fun refreshPanel() {
        if (!panelVisible) return
        val live = StreamState.isStreaming.value
        statusText?.text = if (live) "● LIVE • ${StreamState.status.value}" else "Idle"
        bitrateText?.text = if (live) {
            "Upload: ${StreamState.measuredBitrateBps.value / 1000} kbps • " +
                "Encoder: ${StreamState.encoderBitrateBps.value / 1000} kbps"
        } else {
            "Not streaming"
        }
        updateElapsed()
        val micOn = StreamState.micGain.value > 0
        micButton?.text = if (micOn) "Mic: ON — tap to mute" else "Mic: MUTED — tap to unmute"
        micButton?.background = roundedBg(
            if (micOn) Color.parseColor("#2B2D31") else Color.parseColor("#DA373C"), dp(8)
        )
        squadButton?.text =
            if (StreamState.squadMuted.value) "Squad: MUTED — tap to unmute" else "Squad: ON — tap to mute"
        squadButton?.background = roundedBg(
            if (StreamState.squadMuted.value) Color.parseColor("#DA373C") else Color.parseColor("#2B2D31"), dp(8)
        )
        micSeek?.let { if (it.progress != StreamState.micGain.value) it.progress = StreamState.micGain.value }
        gameSeek?.let { if (it.progress != StreamState.gameGain.value) it.progress = StreamState.gameGain.value }
        startStopButton?.text = if (live) "STOP STREAM" else "START STREAM"
        startStopButton?.background = roundedBg(
            if (live) Color.parseColor("#DA373C") else Color.parseColor("#23A55A"), dp(8)
        )
    }

    private fun updateElapsed() {
        if (!panelVisible) return
        val startedAt = StreamState.startedAtMs.value
        elapsedText?.text = if (startedAt > 0 && StreamState.isStreaming.value) {
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000
            "Live for %02d:%02d".format(secs / 60, secs % 60)
        } else {
            ""
        }
    }

    // ---- teardown ----

    private fun teardown() {
        tickRunning = false
        elapsedHandler.removeCallbacks(elapsedTick)
        hidePanel()
        bubble?.let { windowManager.removeView(it) }
        bubble = null
        bubbleParams = null
        bubbleText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun roundedBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
