package com.koseiedits.squadstream

import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phase 3 (Ably) — voice-room signaling over Ably Realtime.
 *
 * Replaces the Supabase version: Supabase's free tier pauses the project
 * after 7 days of inactivity, and its Kotlin client changed API in 3.9.0.
 * Ably is purpose-built pub/sub — one channel per room code, one event
 * ("signal") carrying SignalMessage JSON as a plain string. No tables, no
 * database, no pause. The API key is the only auth; it is typed into the
 * app once and kept in SharedPreferences, never in code or git.
 *
 * Free tier (verified Nov 2026): 6M messages/month, 200 concurrent
 * connections, 500 messages/second, no credit card, no time limit.
 * A 4-player room uses ~4 connections and a few dozen messages per join.
 *
 * Ably is fire-and-forget, same as Supabase Broadcast was — a lost
 * message means a failed connection, not a retry — which is fine for a
 * 4-person squad room and is called out in the README.
 *
 * API verified against the ably-java 1.8.2 sources (not the docs pages):
 * `AblyRealtime(key)`, `channels.get(name)`, `subscribe(event, listener)`,
 * `publish(event, string)`, `attach(CompletionListener)`.
 */
class AblySignaling(
    private val apiKey: String,
    private val roomCode: String,
) {
    companion object {
        private const val TAG = "AblySignaling"
        const val EVENT = "signal"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 64)
    val incoming: Flow<SignalMessage> = _incoming.asSharedFlow()

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var started = false

    private val messageListener = Channel.MessageListener { message: Message ->
        val text = message.data as? String ?: return@MessageListener
        try {
            val parsed = json.decodeFromString<SignalMessage>(text)
            scope.launch { _incoming.emit(parsed) }
        } catch (e: Exception) {
            Log.w(TAG, "Dropping undecodable signal", e)
        }
    }

    /**
     * Connects, subscribes to the room channel and starts forwarding
     * messages. [onJoined] runs once the channel attach completes — the
     * right moment to announce ourselves (a publish sent before attach
     * is dropped). [onFailed] runs when Ably refuses the connection
     * (wrong/expired key, or no network) so the caller can fail loudly
     * instead of sitting in a dead room.
     */
    fun start(onJoined: (() -> Unit)? = null, onFailed: (() -> Unit)? = null) {
        if (started) return
        started = true
        scope.launch {
            try {
                val client = AblyRealtime(apiKey)
                ably = client
                val ch = client.channels.get("squadstream-room-$roomCode")
                channel = ch
                ch.subscribe(EVENT, messageListener)
                ch.attach(object : CompletionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Attached to room channel $roomCode")
                        onJoined?.invoke()
                    }

                    override fun onError(reason: ErrorInfo) {
                        Log.e(TAG, "Attach failed: ${reason.message}")
                        StreamState.addLog("Voice signaling failed: ${reason.message}")
                        onFailed?.invoke()
                    }
                })
            } catch (e: AblyException) {
                Log.e(TAG, "Ably start failed", e)
                StreamState.addLog("Voice signaling failed: ${e.message}")
                onFailed?.invoke()
            }
        }
    }

    /** Sends one message to the room (or to one peer when msg.to is set). */
    fun send(message: SignalMessage) {
        val ch = channel ?: return
        scope.launch {
            try {
                ch.publish(EVENT, json.encodeToString(message))
            } catch (e: AblyException) {
                Log.w(TAG, "publish failed (${message.type})", e)
            }
        }
    }

    /** Leaves the channel and disconnects. Safe to call twice. */
    fun stop() {
        val ch = channel
        val client = ably
        channel = null
        ably = null
        started = false
        scope.launch {
            try {
                ch?.unsubscribe(EVENT, messageListener)
            } catch (_: Exception) {
            }
            try {
                ch?.detach()
            } catch (_: Exception) {
            }
            try {
                client?.close()
            } catch (_: Exception) {
            }
        }
        scope.cancel()
    }
}
