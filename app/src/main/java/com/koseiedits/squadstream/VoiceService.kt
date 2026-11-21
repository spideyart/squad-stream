package com.koseiedits.squadstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 3 — foreground service hosting the squad voice room.
 *
 * The voice room lives here (not in MainActivity) so it survives while the
 * user is in the game. Foreground type is `microphone` — this service
 * captures the mic for WebRTC. A persistent notification shows the room
 * code and how many mates are in, with a Leave action.
 *
 * The HUD's squad-mute button flips StreamState.squadMuted; this service
 * observes it and applies it to the remote audio tracks.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        const val CHANNEL_ID = "squadstream_voice"
        const val NOTIFY_ID = 1003

        const val ACTION_JOIN = "com.koseiedits.squadstream.ACTION_JOIN_VOICE"
        const val ACTION_LEAVE = "com.koseiedits.squadstream.ACTION_LEAVE_VOICE"
        const val ACTION_TOGGLE_MIC = "com.koseiedits.squadstream.ACTION_TOGGLE_VOICE_MIC"
        const val ACTION_TOGGLE_SPEAKER =
            "com.koseiedits.squadstream.ACTION_TOGGLE_VOICE_SPEAKER"

        const val EXTRA_ABLY_KEY = "ably_key"
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_NAME = "name"

        var INSTANCE: VoiceService? = null
            private set
    }

    private lateinit var roomManager: VoiceRoomManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private var currentCode = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        roomManager = VoiceRoomManager(applicationContext)
        INSTANCE = this
        scope.launch {
            StreamState.squadMuted.collect { muted ->
                roomManager.applySquadMuted(muted)
            }
        }
        scope.launch {
            StreamState.voicePeers.collect { updateNotification() }
        }
        scope.launch {
            StreamState.voiceRoomCode.collect { updateNotification() }
        }
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_JOIN -> handleJoin(intent)
            ACTION_LEAVE -> handleLeave()
            ACTION_TOGGLE_MIC -> {
                roomManager.setMicMuted(!StreamState.voiceMicMuted.value)
            }
            ACTION_TOGGLE_SPEAKER -> {
                roomManager.setSpeakerOn(!StreamState.voiceSpeakerOn.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        try {
            roomManager.release()
        } catch (_: Exception) {
        }
        INSTANCE = null
        super.onDestroy()
    }

    private fun handleJoin(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_ABLY_KEY).orEmpty().trim()
        val code = intent.getStringExtra(EXTRA_ROOM_CODE).orEmpty().trim().uppercase()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        if (key.isBlank()) {
            StreamState.addLog("Voice room needs the Ably API key first")
            stopSelf()
            return
        }
        if (!RoomCodes.isValid(code)) {
            StreamState.addLog("Bad room code — 6 characters, A–Z and 2–9")
            stopSelf()
            return
        }
        if (StreamState.voiceConnected.value && currentCode == code) {
            Log.i(TAG, "Already in room $code")
            return
        }
        currentCode = code
        startForeground(
            NOTIFY_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        roomManager.join(key, code, name)
        updateNotification()
    }

    private fun handleLeave() {
        roomManager.leave()
        currentCode = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SquadStream Voice", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while you are in a squad voice room" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val code = StreamState.voiceRoomCode.value.ifBlank { currentCode }
        val mates = StreamState.voicePeers.value.size
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val leave = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).apply { action = ACTION_LEAVE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Squad voice • Room $code")
            .setContentText(if (mates == 0) "Waiting for squad mates…" else "$mates mate${if (mates == 1) "" else "s"} in the room")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave", leave)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (!StreamState.voiceConnected.value && currentCode.isBlank()) return
        try {
            notificationManager.notify(NOTIFY_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
