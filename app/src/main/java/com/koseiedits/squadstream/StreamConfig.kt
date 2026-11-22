package com.koseiedits.squadstream

/** Which audio goes into the stream. RootEncoder's built-in
 *  MixAudioSource mixes mic + game audio. */
enum class AudioMode { MIX, INTERNAL_ONLY, MIC_ONLY }

data class StreamConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrateBps: Int = 4_000_000,
    val audioMode: AudioMode = AudioMode.MIX,
    val rtmpUrl: String = "rtmp://a.rtmp.youtube.com/live2",
    val streamKey: String = ""
) {
    val label: String get() = "${height}p • ${fps}fps"

    /** YouTube expects the key appended to the RTMP URL with a slash. */
    val fullUrl: String get() = "$rtmpUrl/$streamKey"

    companion object {
        val PRESETS = listOf(
            StreamConfig(1280, 720, 30, 4_000_000),
            StreamConfig(1280, 720, 60, 6_000_000),
            StreamConfig(1920, 1080, 30, 6_000_000)
        )
    }
}
