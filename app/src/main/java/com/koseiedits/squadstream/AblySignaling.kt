package com.koseiedits.squadstream

import android.content.Context
import android.util.Log
import io.ably.pubsub.device.PubSubDevice
import io.ably.pubsub.realtime.Channel
import io.ably.pubsub.realtime.CompletionListener
import io.ably.pubsub.realtime.PubSubRealtimeClient
import io.ably.pubsub.types.AblyException
import io.ably.pubsub.types.ClientOptions
import io.ably.pubsub.types.ErrorInfo
import io.ably.pubsub.types.Message
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
 * Phase 3 (Ably) — voice-room signaling over Ably Pub/Sub.
 *
 * Replaces the Supabase version: Supabase's free tier pauses the project
 * after 7 days of inactivity, and its Kotlin client changed API in 3.9.0.
 * Ably is purpose-built pub/sub — one channel per room code, one event
 * ("signal") carrying SignalMessage JSON as a plain string. No tables, no
 * database, no pause. The API key is the only auth; it is typed into the
 * app once and kept in SharedPreferences, never in code or git.
 *
 * SDK NOTE (Nov 2026) — Ably moved new accounts to MAU pricing, and the
 * old `io.ably:ably-java` 1.8.2 SDK is REJECTED on those accounts. The
 * phone proved it: "This version of the Ably SDK is not compatible with
 * your account, which uses MAU pricing. Use io.ably.pubsub:device 2.0.0+
 * on end-user devices or io.ably.pubsub:server 2.0.0+ on servers,
 * instead of ably-java or ably-android." So this class uses the new
 * `io.ably.pubsub:device:2.0.0` device SDK. MAU pricing also REQUIRES a
 * clientId on every end-user connection — a connection with none is
 * rejected too. The clientId is a stable per-device id (generated once,
 * stored in prefs — see VoiceRoomManager.stableAblyClientId), so one
 * phone counts as one MAU. The free package covers 500 MAUs/month; a
 * 4-player squad uses 4.
 *
 * API verified against Ably's own docs and the ably-pubsub-java
 * UPGRADING.md (package rename from io.ably.lib.* is mechanical):
 * `PubSubDevice.clientBuilder(options).build()`, `setAndroidContext`,
 * `channels.get(name)`, `subscribe(event, listener)`,
 * `publish(event, string)`, `attach(CompletionListener)`.
 *
 * Ably is fire-and-forget, same as Supabase Broadcast was — a lost
 * message means a failed connection, not a retry — which is fine for a
 * 4-person squad room and is called out in the README.
 */
class AblySignaling(
    private val apiKey: String,
    private val roomCode: String,
    private val clientId: String,
    private val context: Context,
) {
    companion object {
        private const val TAG = "AblySignaling"
        const val EVENT = "signal"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 64)
    val incoming: Flow<SignalMessage> = _incoming.asSharedFlow()

    private var ably: PubSubRealtimeClient? = null
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
     * instead of sitting in a dead room. It carries the raw reason, so
     * the caller does not have to guess — an earlier version always
     * blamed the key, even when Ably was rejecting the SDK itself.
     */
    fun start(onJoined: (() -> Unit)? = null, onFailed: ((String?) -> Unit)? = null) {
        if (started) return
        started = true
        scope.launch {
            try {
                val options = ClientOptions().apply {
                    key = apiKey
                    clientId = clientId
                }
                val client = PubSubDevice.clientBuilder(options).build()
                client.setAndroidContext(context.applicationContext)
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
                        onFailed?.invoke(reason.message)
                    }
                })
            } catch (e: AblyException) {
                Log.e(TAG, "Ably start failed", e)
                StreamState.addLog("Voice signaling failed: ${e.message}")
                onFailed?.invoke(e.message)
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
