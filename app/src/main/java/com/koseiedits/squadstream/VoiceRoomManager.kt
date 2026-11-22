package com.koseiedits.squadstream

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 — 4-person WebRTC mesh voice room (audio only).
 *
 * Mesh, no server in the middle: every peer holds one PeerConnection per
 * other peer (max 3). Signaling rides Ably Realtime (see AblySignaling).
 * One rule keeps the mesh glare-free: when two peers learn of each other, the one with the LOWER peer id sends the offer;
 * the other waits and answers. Both sides compute the same offerer from
 * the same two ids, so nobody offers twice.
 *
 * Audio: the local mic goes out through WebRTC's own audio device module
 * (VOICE_COMMUNICATION + hardware echo canceler + noise suppressor).
 * Teammates hear ONLY the mic — never game audio, never their own voice
 * back. Remote voices play through the phone speaker by default.
 *
 * All PeerConnection work runs on one dedicated thread ("webrtc").
 * StreamState is the only cross-thread surface (StateFlow is thread-safe).
 *
 * Honest limits (also in the README): the mic is captured twice while
 * streaming (RootEncoder's MixAudioSource + WebRTC) — works on most
 * phones, watch for echo on the test. Signaling is fire-and-forget, so a
 * lost offer means that peer never connects until someone rejoins. Only
 * free public STUN — two players behind symmetric NATs may fail to
 * connect (no TURN yet).
 */
class VoiceRoomManager(private val appContext: Context) {

    companion object {
        private const val TAG = "VoiceRoomManager"
        private var factoryInitialized = false
    }

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val rtcThread = HandlerThread("webrtc").apply { start() }
    private val rtcHandler = Handler(rtcThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var signaling: AblySignaling? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val remoteTracks = ConcurrentHashMap<String, AudioTrack>()

    private var myId = ""
    private var myName = ""
    private var roomCode = ""
    private var joined = false
    private var micMuted = false
    private var speakerOn = true

    /**
     * Stable per-device Ably clientId, generated once and kept in prefs.
     * MAU pricing counts one unique clientId per phone per month — and a
     * connection with NO clientId is rejected outright — so this must be
     * stable across joins (a fresh id every join would burn MAUs) and
     * must never be the per-join peer id. One phone = one MAU; the free
     * package covers 500, a 4-player squad uses 4.
     */
    private fun stableAblyClientId(): String {
        val prefs = appContext.getSharedPreferences(HudService.PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("ably_client_id", "").orEmpty()
        if (existing.isNotBlank()) return existing
        val fresh = "ss-" + UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString("ably_client_id", fresh).apply()
        return fresh
    }

    // ---- join / leave ----

    fun join(apiKey: String, code: String, name: String) {
        rtcHandler.post {
            if (joined) {
                if (roomCode == code) return@post
                leaveInternal()
            }
            myId = newPeerId()
            myName = name.ifBlank { "Player" }
            roomCode = code
            try {
                initFactoryIfNeeded()
                createLocalTrack()
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC init failed", e)
                StreamState.addLog("Voice room failed: ${e.message}")
                return@post
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speakerOn
            val sig = AblySignaling(apiKey, code, stableAblyClientId(), appContext)
            signaling = sig
            scope.launch { sig.incoming.collect { handleSignal(it) } }
            sig.start(
                onJoined = {
                    sig.send(
                        SignalMessage(
                            type = SignalTypes.JOIN, from = myId, name = myName,
                            muted = micMuted,
                        )
                    )
                    StreamState.setVoiceConnected(true)
                    StreamState.addLog("Joined voice room $code")
                },
                onFailed = { reason -> handleSignalingFailed(reason) },
            )
            joined = true
            StreamState.setVoiceRoomCode(code)
            StreamState.addLog("Connecting to voice room $code…")
            startLevelPoll()
        }
    }

    fun leave() {
        rtcHandler.post { leaveInternal() }
    }

    private fun leaveInternal() {
        if (!joined) return
        stopLevelPoll()
        signaling?.send(SignalMessage(type = SignalTypes.LEAVE, from = myId))
        // Let the leave message go out before tearing the channel down.
        rtcHandler.postDelayed({
            signaling?.stop()
            signaling = null
        }, 300)
        closeAllPeers()
        disposeLocalTrack()
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        joined = false
        StreamState.resetVoice()
        StreamState.addLog("Left voice room")
    }

    /** Full teardown (service going away). */
    fun release() {
        rtcHandler.post {
            if (joined) leaveInternal()
            try {
                factory?.dispose()
            } catch (_: Exception) {
            }
            factory = null
            rtcThread.quitSafely()
        }
        scope.cancel()
    }

    /**
     * Ably could not connect (wrong/expired key, or no network). Fail
     * loudly instead of sitting in a dead room: the app used to mark
     * itself "connected" before attach and then show "Waiting for squad
     * mates…" forever while signaling was dead — no names, no voice, the
     * mic never started, and nothing told the user why.
     *
     * The raw failure is already in the log (AblySignaling logs it), so
     * this only guesses at the key when there is no specific reason —
     * an earlier version always blamed the key, even when Ably was
     * rejecting the SDK itself, which sent the debugging down the wrong
     * path for a whole morning.
     */
    private fun handleSignalingFailed(reason: String?) {
        rtcHandler.post {
            if (!joined) return@post
            if (reason.isNullOrBlank()) {
                StreamState.addLog("Voice room couldn't connect — key may be wrong or expired")
            }
            leaveInternal()
            try {
                signaling?.stop()
            } catch (_: Exception) {
            }
            try {
                appContext.stopService(Intent(appContext, VoiceService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    // ---- mic / speaker / squad mute ----

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        rtcHandler.post { localTrack?.setEnabled(!muted) }
        StreamState.setVoiceMicMuted(muted)
        // Tell the room so their screens show my muted icon.
        signaling?.send(SignalMessage(type = SignalTypes.MIC, from = myId, muted = muted))
    }

    fun setSpeakerOn(on: Boolean) {
        speakerOn = on
        try {
            audioManager.isSpeakerphoneOn = on
        } catch (_: Exception) {
        }
        StreamState.setVoiceSpeakerOn(on)
    }

    /** Mutes the squad voices I hear (HUD squad-mute). Mic to them is untouched. */
    fun applySquadMuted(muted: Boolean) {
        rtcHandler.post {
            remoteTracks.values.forEach { it.setEnabled(!muted) }
        }
    }

    // ---- WebRTC setup ----

    private fun initFactoryIfNeeded() {
        if (factory != null) return
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    private fun createLocalTrack() {
        val f = factory ?: throw IllegalStateException("No PeerConnectionFactory")
        val source = f.createAudioSource(MediaConstraints())
        audioSource = source
        localTrack = f.createAudioTrack("audio_$myId", source).apply {
            setEnabled(!micMuted)
        }
    }

    private fun disposeLocalTrack() {
        try {
            localTrack?.dispose()
        } catch (_: Exception) {
        }
        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        localTrack = null
        audioSource = null
    }

    // ---- signaling ----

    private fun handleSignal(msg: SignalMessage) {
        rtcHandler.post { handleSignalOnRtcThread(msg) }
    }

    private fun handleSignalOnRtcThread(msg: SignalMessage) {
        if (!joined || msg.from == myId) return
        if (msg.to != null && msg.to != myId) return
        when (msg.type) {
            SignalTypes.JOIN -> {
                // Room cap: 4 players (me + 3 others). A 5th joiner gets
                // turned away with FULL instead of silently joining a
                // 5-way mesh the grid was never built for.
                if (peerNames.size >= 3) {
                    signaling?.send(
                        SignalMessage(type = SignalTypes.FULL, from = myId, to = msg.from)
                    )
                    return
                }
                peerNames[msg.from] = msg.name ?: "Player"
                StreamState.setPeerMuted(msg.from, msg.muted)
                signaling?.send(
                    SignalMessage(
                        type = SignalTypes.HELLO, from = myId,
                        to = msg.from, name = myName, muted = micMuted,
                    )
                )
                // The lower peer id offers — do it here too. If I joined
                // first, the other side never saw my JOIN, so no HELLO will
                // ever come back to me; waiting for one deadlocked the pair
                // half the time (names showed, but no call ever started).
                if (myId < msg.from) createOfferTo(msg.from)
                updatePeersFlow()
            }
            SignalTypes.HELLO -> {
                peerNames[msg.from] = msg.name ?: "Player"
                StreamState.setPeerMuted(msg.from, msg.muted)
                updatePeersFlow()
                // Lower peer id offers; the other side waits for the offer.
                if (myId < msg.from) createOfferTo(msg.from)
            }
            SignalTypes.OFFER -> {
                val sdp = msg.sdp ?: return
                val pc = getOrCreatePc(msg.from) ?: return
                pc.setRemoteDescription(
                    SimpleSdpObserver(onSet = {
                        pc.createAnswer(
                            SimpleSdpObserver(onCreate = { answer ->
                                pc.setLocalDescription(SimpleSdpObserver(), answer)
                                signaling?.send(
                                    SignalMessage(
                                        type = SignalTypes.ANSWER, from = myId,
                                        to = msg.from, sdp = answer.description,
                                    )
                                )
                            }),
                            offerConstraints(),
                        )
                    }),
                    SessionDescription(SessionDescription.Type.OFFER, sdp),
                )
            }
            SignalTypes.ANSWER -> {
                val sdp = msg.sdp ?: return
                peers[msg.from]?.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp),
                )
            }
            SignalTypes.ICE -> {
                val c = msg.candidate ?: return
                peers[msg.from]?.addIceCandidate(
                    IceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
                )
            }
            SignalTypes.LEAVE -> removePeer(msg.from)
            SignalTypes.MIC -> StreamState.setPeerMuted(msg.from, msg.muted)
            SignalTypes.FULL -> {
                // Turned away: the room is already full (4 players).
                if (msg.to == myId) handleRoomFull()
            }
        }
    }

    /** Turned away from a full room: leave cleanly and say so. */
    private fun handleRoomFull() {
        rtcHandler.post {
            if (!joined) return@post
            StreamState.addLog("Room is full (4 players)")
            leaveInternal()
            try {
                signaling?.stop()
            } catch (_: Exception) {
            }
            try {
                appContext.stopService(Intent(appContext, VoiceService::class.java))
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "Room is full (4 players)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun offerConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
    }

    private fun getOrCreatePc(peerId: String): PeerConnection? {
        peers[peerId]?.let { return it }
        val f = factory ?: return null
        val config = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                    .createIceServer(),
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = try {
            f.createPeerConnection(config, PeerObserver(peerId))
        } catch (e: Exception) {
            Log.w(TAG, "createPeerConnection failed", e)
            null
        } ?: return null
        try {
            localTrack?.let { pc.addTrack(it, listOf("local_stream")) }
        } catch (e: Exception) {
            Log.w(TAG, "addTrack failed", e)
        }
        peers[peerId] = pc
        return pc
    }

    private fun createOfferTo(peerId: String) {
        val pc = getOrCreatePc(peerId) ?: return
        pc.createOffer(
            SimpleSdpObserver(onCreate = { offer ->
                pc.setLocalDescription(SimpleSdpObserver(), offer)
                signaling?.send(
                    SignalMessage(
                        type = SignalTypes.OFFER, from = myId,
                        to = peerId, sdp = offer.description,
                    )
                )
            }),
            offerConstraints(),
        )
    }

    private fun removePeer(peerId: String) {
        try {
            peers.remove(peerId)?.close()
        } catch (_: Exception) {
        }
        remoteTracks.remove(peerId)
        peerNames.remove(peerId)
        StreamState.removeVoiceLevel(peerId)
        StreamState.removePeerMuted(peerId)
        updatePeersFlow()
        StreamState.addLog("Squad mate left")
    }

    private fun closeAllPeers() {
        peers.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        peers.clear()
        remoteTracks.clear()
        peerNames.clear()
        updatePeersFlow()
    }

    private fun updatePeersFlow() {
        StreamState.setVoicePeers(peerNames.values.sorted())
        StreamState.setVoicePeerMap(peerNames.toMap())
    }

    // ---- speaking levels (Discord-style: who is talking, how loud) ----
    //
    // WebRTC's getStats reports an audioLevel (0..1) on each audio track.
    // Poll every 150 ms while in the room and push the levels to
    // StreamState; the UI draws a green ring + level bar per person.
    // Honest limit: if this WebRTC build ever stops reporting audioLevel
    // for remote tracks, the rings stay dark — the voice itself is
    // unaffected, only the indicator goes quiet.

    private val levelPoll = object : Runnable {
        override fun run() {
            if (!joined) return
            pollLevels()
            rtcHandler.postDelayed(this, 150)
        }
    }

    private fun startLevelPoll() {
        rtcHandler.removeCallbacks(levelPoll)
        rtcHandler.post(levelPoll)
    }

    private fun stopLevelPoll() {
        rtcHandler.removeCallbacks(levelPoll)
    }

    private fun pollLevels() {
        val localId = localTrack?.id()
        peers.forEach { (_, pc) ->
            try {
                pc.getStats(RTCStatsCollectorCallback { report ->
                    var myLevel = -1f
                    val peerLevels = mutableMapOf<String, Float>()
                    for (stat in report.statsMap.values) {
                        val level =
                            (stat.members["audioLevel"] as? Number)?.toFloat() ?: continue
                        when (stat.type) {
                            "track" -> {
                                val trackId = stat.members["trackIdentifier"] as? String
                                    ?: continue
                                if (trackId == localId) {
                                    myLevel = level
                                } else {
                                    remoteTracks.entries
                                        .firstOrNull { it.value.id() == trackId }
                                        ?.let { peerLevels[it.key] = level }
                                }
                            }
                            "media-source" -> {
                                // Fallback for my own mic level.
                                if (stat.members["kind"] == "audio" && myLevel < 0f) {
                                    myLevel = level
                                }
                            }
                        }
                    }
                    if (myLevel >= 0f) StreamState.setMyVoiceLevel(myLevel)
                    peerLevels.forEach { (peerId, level) ->
                        StreamState.setVoiceLevel(peerId, level)
                    }
                })
            } catch (_: Exception) {
            }
        }
    }

    // ---- PeerConnection observer (one per peer) ----

    private inner class PeerObserver(private val peerId: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling?.send(
                SignalMessage(
                    type = SignalTypes.ICE, from = myId, to = peerId,
                    candidate = IcePayload(
                        sdp = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                )
            )
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
            val track = receiver.track() as? AudioTrack ?: return
            track.setEnabled(!StreamState.squadMuted.value)
            remoteTracks[peerId] = track
            Log.i(TAG, "Remote audio track from $peerId")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE $peerId -> $state")
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                // Never close a PeerConnection from inside its own observer
                // callback — hand the cleanup to the rtc thread.
                rtcHandler.post { removePeer(peerId) }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private class SimpleSdpObserver(
        private val onCreate: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String) {
            Log.w(TAG, "SDP create failed: $error")
        }

        override fun onSetFailure(error: String) {
            Log.w(TAG, "SDP set failed: $error")
        }
    }
}
