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

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun setStreaming(streaming: Boolean) { _isStreaming.value = streaming }
    fun setStatus(text: String) { _status.value = text }
    fun setMeasuredBitrate(bps: Long) { _measuredBitrateBps.value = bps }
    fun setEncoderBitrate(bps: Int) { _encoderBitrateBps.value = bps }

    fun addLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$time] $line").takeLast(6)
    }

    fun reset() {
        _isStreaming.value = false
        _status.value = "Idle"
        _measuredBitrateBps.value = 0L
        _encoderBitrateBps.value = 0
    }
}
