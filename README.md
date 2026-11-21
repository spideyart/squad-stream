# SquadStream — Phase 1

A lightweight, zero-cost Android live-streaming app (Kotlin + Jetpack Compose)
for a 4-player mobile gaming squad. Inspired by Turnip.

**Phase 1 does:** capture the screen + internal game audio + mic and push
H.264 + AAC straight to YouTube over RTMP. No server, no paid service.

## How it works

```
Free Fire MAX (game)
   │  screen frames ──► MediaProjection ──► ScreenSource ──┐
   │  game audio ─────► AudioPlaybackCapture ──► MixAudioSource ─┤
   │  mic ───────────► VOICE_COMMUNICATION + AEC/NS ────────────┤
   │                                                             ▼
   │                                              GenericStream (RootEncoder 2.8.2)
   │                                              H.264 video + AAC audio
   │                                                             │
   └─────────────────────────────────────────────────────────────┘
                                                              RTMP
                                                                ▼
                                                    YouTube Live (rtmp://a.rtmp.youtube.com/live2/<key>)
```

Key facts (verified against the RootEncoder 2.8.2 docs + official screen example):

- `GenericStream` with `ScreenSource` + `MixAudioSource` is the reference
  pattern. `MixAudioSource` mixes mic + internal game audio **inside the
  library** — so Phase 1 already has both. The custom 3-channel PCM mixer
  in Phase 4 is still needed, because it adds the third source the library
  cannot mix: incoming WebRTC squad voices.
- `prepareAudio(44100, stereo, 128 kbps, echoCanceler = true,
  noiseSuppressor = true)` — the echo canceler / noise suppressor run on the
  mic side of the mix.
- `getGlInterface().setForceRender(true, 15)` keeps a steady frame floor:
  MediaProjection only produces frames when the screen changes.
- Adaptive bitrate is simple and conservative: `onNewBitrate` reports
  measured upload throughput; the encoder steps down (min 800 kbps) only
  after two bad readings, steps back up toward the configured target, and
  never changes more often than every 10 s.
- The `● LIVE SQUADSTREAM` badge is a best-effort OpenGL overlay
  (`AndroidViewFilterRender`). If it ever fails, the stream continues
  without it — overlay code is wrapped so it can never break streaming.

## Android 14+ foreground-service rules (followed exactly)

1. Consent first: `MediaProjectionManager.createScreenCaptureIntent()` is
   launched from `MainActivity` **before** any service starts.
2. The service then calls `startForeground()` with types
   `mediaProjection|microphone` (declared in the manifest the same way).
3. Only then is `MediaProjectionManager.getMediaProjection()` called.
   Calling it before `startForeground()` throws `SecurityException` on API 34+.
4. `RECORD_AUDIO` + `POST_NOTIFICATIONS` are runtime permissions, asked
   on first Start. `FOREGROUND_SERVICE*` are install-time: declared only.

## Folder structure

```
squad-stream/
├── settings.gradle.kts            # repos (google, mavenCentral, JitPack)
├── build.gradle.kts               # root plugins
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml         # AGP 8.7.3, Kotlin 2.0.21, Compose BOM, RootEncoder 2.8.2
│   └── wrapper/gradle-wrapper.properties   # Gradle 8.11
├── .github/workflows/android.yml  # cloud build → debug APK (no PC needed)
└── app/
    ├── build.gradle.kts           # minSdk 29, targetSdk 35, compileSdk 35
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    # permissions + foreground service types
        └── java/com/koseiedits/squadstream/
            ├── MainActivity.kt    # Compose UI: URL/key, quality, audio mode, start/stop
            ├── StreamService.kt   # foreground service: capture → encode → RTMP
            ├── StreamConfig.kt    # quality presets + audio modes
            └── StreamState.kt     # service → UI status flows
```

## Getting the APK (no PC needed)

1. Create an **empty** repo on GitHub named `squad-stream` (public or
   private — your call; the stream key is never committed either way).
2. Tell Muse the repo exists; the project gets pushed and GitHub Actions
   builds `assembleDebug` in the cloud.
3. In the repo, open the **Actions** tab → the latest run → download the
   `squadstream-debug-apk` artifact. Install the APK on your phone.
4. Every later push rebuilds the APK automatically.

## YouTube RTMP setup (do this once)

1. On YouTube Studio (desktop site in your phone browser works): **Go Live**
   → **Stream settings**.
2. Copy the **Stream URL** — `rtmp://a.rtmp.youtube.com/live2` (already the
   app default) — and the **Stream key**.
3. Paste the key into the app. Never share it: anyone with it can stream
   to your channel. The app keeps it in memory only and never saves it.

## Phase 1 verification test

Do each step in order. If a step fails, stop and read the troubleshooting
table — don't skip ahead.

1. **Install + launch.** App opens, no crash. Grant mic + notification
   permission when asked.
2. **Fill in the key.** Paste a real YouTube stream key. Leave the URL
   default. Quality: 720p • 30fps. Audio: Mic + game audio.
3. **Start.** Tap START STREAM → allow the screen-capture prompt
   ("Start now"). Status should go Connecting → Live within ~10 s.
   A "SquadStream — LIVE" notification appears with a Stop button.
4. **YouTube sees it.** In YouTube Studio → Go Live, the preview shows
   your phone screen within ~30 s. The red `● LIVE SQUADSTREAM` badge is
   in the top-left corner.
5. **Game audio.** Open Free Fire MAX and play. Viewers (or the Studio
   preview) hear game sound. Talk: your mic is in the mix too.
6. **Adaptive bitrate.** Watch the "Upload / Encoder" line in the app.
   If your upload dips, the encoder bitrate steps down and the log says
   `Bitrate adapted → … kbps`.
7. **Stop.** Tap STOP in the app (or the notification). Status returns to
   Idle, notification disappears, YouTube ends the stream. No crash,
   no lingering notification.
8. **Revoke test (optional).** While live, swipe the capture permission
   away from the system UI — the stream must stop cleanly, not hang.

Pass = steps 1–7 all work on your Xiaomi phone while Free Fire MAX is
running, with no crash and no overheating warning in 10 minutes of play.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Auth error — check stream key` | Wrong / expired key | Re-copy the key from YouTube Studio; keys change if you reset them |
| `Failed: …` / never connects | No network, or wrong RTMP URL | Check mobile data/Wi-Fi; URL must be `rtmp://a.rtmp.youtube.com/live2` |
| Black screen in preview | Game not producing frames | `setForceRender(true, 15)` is already on; make sure the game is in the foreground |
| No game audio | Audio mode wrong | Pick "Mic + game audio (mix)"; game must actually be playing sound |
| No mic audio | Mic permission denied | Settings → Apps → SquadStream → Permissions → Microphone → Allow |
| Crash on Start (Android 14) | Foreground service type | Already handled: manifest declares `mediaProjection\|microphone` and the service passes both to `startForeground()` |
| Badge missing, stream fine | Overlay failed | Cosmetic only — the overlay is best-effort by design; stream is unaffected |

## Known limits (honest)

- **Rotation:** Phase 1 encodes landscape 1280×720, rotation 0. If you
  start the stream from a portrait screen, the image is scaled, not
  rotated. A rotation-aware mode comes with the Phase 2 HUD work.
- **No squad voice yet:** Phase 1 is stream-only. Voice rooms arrive in
  Phase 3; the 3-channel mixer (game + mic + squad) in Phase 4.
- **No floating controls yet:** starting/stopping is from the app or the
  notification. The in-game bubble is Phase 2.
- **Upload is the bottleneck:** 720p30 at 4 Mbps needs a stable ~5 Mbps
  upload. If your mobile data is slow, drop to the adaptive floor or
  stream on Wi-Fi.

## Next: Phase 2

Floating overlay HUD (`SYSTEM_ALERT_WINDOW` bubble): start/stop, mic mute,
squad mute, volume mix sliders, live bitrate/FPS indicator — all without
leaving the game.
