package com.koseiedits.squadstream

import android.util.Log
import io.github.jan-tennert.supabase.createSupabaseClient
import io.github.jan-tennert.supabase.realtime.Realtime
import io.github.jan-tennert.supabase.realtime.RealtimeChannel
import io.github.jan-tennert.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Phase 3 — voice-room signaling over Supabase Realtime Broadcast.
 *
 * No tables, no row-level security, no login: one Broadcast channel per
 * room code, one event ("signal") carrying SignalMessage JSON. The project's
 * anon key is all the auth there is. Broadcast is fire-and-forget — a lost
 * message means a failed connection, not a retry — which is fine for a
 * 4-person squad room and is called out in the README.
 */
class SupabaseSignaling(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val roomCode: String,
) {
    companion object {
        private const val TAG = "SupabaseSignaling"
        const val EVENT = "signal"
    }

    private val client = createSupabaseClient(supabaseUrl, supabaseKey) {
        install(Realtime)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 64)
    val incoming: Flow<SignalMessage> = _incoming.asSharedFlow()

    private var channel: RealtimeChannel? = null
    private var started = false

    /**
     * Connects, joins the room channel and starts forwarding messages.
     * [onJoined] runs once the channel join completes — the right moment
     * to announce ourselves (a broadcast sent before join is dropped).
     */
    fun start(onJoined: (() -> Unit)? = null) {
        if (started) return
        started = true
        scope.launch {
            try {
                val ch = client.realtime.createChannel("squadstream-room-$roomCode")
                channel = ch
                scope.launch {
                    try {
                        ch.broadcastFlow<SignalMessage>(event = EVENT).collect {
                            _incoming.emit(it)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "broadcastFlow ended", e)
                    }
                }
                client.realtime.connect()
                ch.join(blockUntilJoined = true)
                Log.i(TAG, "Joined room channel $roomCode")
                onJoined?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Signaling start failed", e)
                StreamState.addLog("Voice signaling failed: ${e.message}")
            }
        }
    }

    /** Sends one message to the room (or to one peer when msg.to is set). */
    fun send(message: SignalMessage) {
        val ch = channel ?: return
        scope.launch {
            try {
                ch.broadcast(event = EVENT, value = message)
            } catch (e: Exception) {
                Log.w(TAG, "broadcast failed (${message.type})", e)
            }
        }
    }

    /** Leaves the channel and disconnects. Safe to call twice. */
    fun stop() {
        val ch = channel
        channel = null
        started = false
        scope.launch {
            try {
                if (ch != null) client.realtime.removeChannel(ch)
            } catch (_: Exception) {
            }
            try {
                client.realtime.disconnect()
            } catch (_: Exception) {
            }
        }
        scope.cancel()
    }
}
