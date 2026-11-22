package com.koseiedits.squadstream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared, process-local stream status. The service writes, the Compose UI reads. */
object StreamState {
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Measured upload throughput in bits per second (RootEncoder onNewBitrate). */
    private val _measuredBitrateBps = MutableStateFlow(0L)
    val measuredBitrateBps: StateFlow<Long> = _measuredBitrateBps.asStateFlow()

    /** Bitrate the encoder is currently set to (drops below target when adapting). */
    private val _encoderBitrateBps = MutableStateFlow(0)
    val encoderBitrateBps: StateFlow<Int> = _encoderBitrateBps.asStateFlow()

    /** Mic / game mix gains, 0–100. The HUD sliders write these live. */
    private val _micGain = MutableStateFlow(100)
    val micGain: StateFlow<Int> = _micGain.asStateFlow()

    private val _gameGain = MutableStateFlow(100)
    val gameGain: StateFlow<Int> = _gameGain.asStateFlow()

    /** Squad mute toggle. No effect until Phase 3 (no squad voices yet). */
    private val _squadMuted = MutableStateFlow(false)
    val squadMuted: StateFlow<Boolean> = _squadMuted.asStateFlow()

    /** Elapsed-realtime clock when the current stream started (0 = not streaming). */
    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs.asStateFlow()

    // ---- Phase 3: squad voice room (independent of streaming) ----
    private val _voiceRoomCode = MutableStateFlow("")
    val voiceRoomCode: StateFlow<String> = _voiceRoomCode.asStateFlow()

    private val _voiceConnected = MutableStateFlow(false)
    val voiceConnected: StateFlow<Boolean> = _voiceConnected.asStateFlow()

    /** Display names of the other peers in the room (not including you). */
    private val _voicePeers = MutableStateFlow<List<String>>(emptyList())
    val voicePeers: StateFlow<List<String>> = _voicePeers.asStateFlow()

    /** Peer id → display name, for the Discord-style speaking rows. */
    private val _voicePeerMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val voicePeerMap: StateFlow<Map<String, String>> = _voicePeerMap.asStateFlow()

    /** Peer id → live mic level 0..1, from WebRTC stats (~3x/sec). */
    private val _voiceLevels = MutableStateFlow<Map<String, Float>>(emptyMap())
    val voiceLevels: StateFlow<Map<String, Float>> = _voiceLevels.asStateFlow()

    /** My own mic level 0..1. */
    private val _myVoiceLevel = MutableStateFlow(0f)
    val myVoiceLevel: StateFlow<Float> = _myVoiceLevel.asStateFlow()

    /** Peer id → mic-muted state, from the signaled `mic` messages. */
    private val _peerMuted = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val peerMuted: StateFlow<Map<String, Boolean>> = _peerMuted.asStateFlow()

    private val _voiceMicMuted = MutableStateFlow(false)
    val voiceMicMuted: StateFlow<Boolean> = _voiceMicMuted.asStateFlow()

    private val _voiceSpeakerOn = MutableStateFlow(true)
    val voiceSpeakerOn: StateFlow<Boolean> = _voiceSpeakerOn.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun setStreaming(streaming: Boolean) { _isStreaming.value = streaming }
    fun setStatus(text: String) { _status.value = text }
    fun setMeasuredBitrate(bps: Long) { _measuredBitrateBps.value = bps }
    fun setEncoderBitrate(bps: Int) { _encoderBitrateBps.value = bps }
    fun setMicGain(gain: Int) { _micGain.value = gain.coerceIn(0, 100) }
    fun setGameGain(gain: Int) { _gameGain.value = gain.coerceIn(0, 100) }
    fun setSquadMuted(muted: Boolean) { _squadMuted.value = muted }
    fun setStartedAt(ms: Long) { _startedAtMs.value = ms }
    fun setVoiceRoomCode(code: String) { _voiceRoomCode.value = code }
    fun setVoiceConnected(connected: Boolean) { _voiceConnected.value = connected }
    fun setVoicePeers(peers: List<String>) { _voicePeers.value = peers }
    fun setVoicePeerMap(peers: Map<String, String>) { _voicePeerMap.value = peers }
    fun setVoiceLevel(peerId: String, level: Float) {
        _voiceLevels.value = _voiceLevels.value + (peerId to level.coerceIn(0f, 1f))
    }
    fun removeVoiceLevel(peerId: String) {
        _voiceLevels.value = _voiceLevels.value - peerId
    }
    fun setMyVoiceLevel(level: Float) { _myVoiceLevel.value = level.coerceIn(0f, 1f) }
    fun setPeerMuted(peerId: String, muted: Boolean) {
        _peerMuted.value = _peerMuted.value + (peerId to muted)
    }
    fun removePeerMuted(peerId: String) {
        _peerMuted.value = _peerMuted.value - peerId
    }
    fun setVoiceMicMuted(muted: Boolean) { _voiceMicMuted.value = muted }
    fun setVoiceSpeakerOn(on: Boolean) { _voiceSpeakerOn.value = on }

    fun addLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$time] $line").takeLast(6)
    }

    fun reset() {
        _isStreaming.value = false
        _status.value = "Idle"
        _measuredBitrateBps.value = 0L
        _encoderBitrateBps.value = 0
        _micGain.value = 100
        _gameGain.value = 100
        _squadMuted.value = false
        _startedAtMs.value = 0L
    }

    /** Clears voice-room state only (stream state is untouched). */
    fun resetVoice() {
        _voiceRoomCode.value = ""
        _voiceConnected.value = false
        _voicePeers.value = emptyList()
        _voicePeerMap.value = emptyMap()
        _voiceLevels.value = emptyMap()
        _peerMuted.value = emptyMap()
        _myVoiceLevel.value = 0f
        _voiceMicMuted.value = false
        _voiceSpeakerOn.value = true
    }
}
