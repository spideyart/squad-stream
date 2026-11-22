package com.koseiedits.squadstream

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

/**
 * Phase 3 — voice-room signaling messages.
 *
 * One JSON message type rides the Ably Realtime channel (event
 * "signal"). `to == null` means "everyone in the room"; otherwise the
 * message is for one peer only (offers, answers and ICE are unicast).
 */
@Serializable
data class SignalMessage(
    val type: String, // join | hello | offer | answer | ice | leave | mic
    val from: String, // sender peer id
    val to: String? = null,
    val name: String? = null, // display name (join / hello)
    val sdp: String? = null, // offer / answer SDP
    val candidate: IcePayload? = null, // ICE candidate
    // Mic-mute state (join / hello / mic). Peers show the muted icon
    // from this — a peer's mic state is otherwise invisible to the room.
    val muted: Boolean = false,
)

@Serializable
data class IcePayload(
    val sdp: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

object SignalTypes {
    const val JOIN = "join"
    const val HELLO = "hello"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE = "ice"
    const val LEAVE = "leave"
    const val MIC = "mic" // mic-mute state broadcast (muted = true/false)
}

/** 6-character room codes. Alphabet skips look-alikes (0/O, 1/I/L). */
object RoomCodes {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(): String =
        (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun isValid(code: String): Boolean =
        code.length == 6 && code.all { it in ALPHABET }
}

/** Short random peer id, unique per join (not the display name). */
fun newPeerId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
