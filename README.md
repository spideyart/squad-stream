# SquadStream — Phase 3

A lightweight, zero-cost Android live-streaming app (Kotlin + Jetpack Compose)
for a 4-player mobile gaming squad. Inspired by Turnip.

**Phase 1 does:** capture the screen + internal game audio + mic and push
H.264 + AAC straight to YouTube over RTMP. No server, no paid service.

**Phase 2 adds:** a floating HUD bubble over the game (start/stop, mic mute,
mic/game mix sliders, live bitrate + elapsed time) — no need to leave the
game to control the stream.

**Phase 3 adds:** a squad voice room — up to 4 players talking over WebRTC
while they play, joined by a 6-character room code. No server of ours: the
only backend is a free Ably account used for signaling.

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
- The `● LIVE SQUADSTREAM` watermark is a best-effort OpenGL overlay
  (`AndroidViewFilterRender`), sized to the stream frame (~1.6% of frame
  width) and fading in/out on a slow loop. If it ever fails, the stream
  continues without it — overlay code is wrapped so it can never break
  streaming.
- App icon: an adaptive icon (white play triangle + red live dot on dark,
  `res/mipmap-anydpi-v26/` + `res/drawable/ic_launcher_foreground.xml`).

## Phase 2 — floating HUD

`HudService` draws a draggable bubble over any app with
`WindowManager.TYPE_APPLICATION_OVERLAY`. It needs the
**"Display over other apps"** permission (`SYSTEM_ALERT_WINDOW`): turn it
on in the app under *Floating controls*, which opens the system Settings
page for SquadStream. The bubble is a foreground service of type
`specialUse` (Android 14+ requires every foreground service to declare a
type, and the bubble fits no other type); on older Android it runs as a
plain foreground service. A low-priority "SquadStream controls"
notification keeps it alive, with a Hide action.

Tap the bubble to open the panel:

- **● LIVE / Idle + elapsed time** and **live upload / encoder bitrate**.
- **Mic mute** — swaps the audio source (`MixAudioSource` ↔
  `InternalAudioSource`), so the game keeps playing for viewers while
  your mic goes silent. `changeAudioSource` is safe while streaming.
- **Mic / Game sliders** — 0–100 each. The library has no per-source
  gain, so a slider at 0 switches its source off (Mix ↔ Mic-only ↔
  Game-only ↔ silence). Mid-range positions don't change volume yet —
  true per-channel gain (0–100 volume, not just on/off) arrives with the
  Phase 4 mixer. The audio-mode radio in the app is a preset for the
  same two gains.
- **Squad mute** — shown disabled until Phase 3 (no squad voices yet).
- **START / STOP** — Stop ends the stream from the bubble. Start opens
  the app and begins the capture-consent flow at once (only works if a
  stream key is already pasted; the key is never saved, so paste it in
  the app first).

The bubble remembers its position. Drag it anywhere; the panel opens
below it (or above it if there is no room).

## Phase 2 verification test

1. **Enable.** In the app: *Floating controls* → "Allow display over
   other apps" → turn SquadStream on in Settings → back in the app,
   "Enable floating controls". The bubble appears.
2. **Drag.** Drag the bubble to a corner. It stays there; reopen the app
   later and it is back in the same spot.
3. **Panel.** Tap the bubble: the panel opens with Idle status, Mic ON,
   both sliders at 100, squad mute greyed out.
4. **Start from the app** (paste the key, START STREAM). The bubble turns
   red: `● LIVE`. The panel shows elapsed time and a live bitrate.
5. **Mic mute.** Tap "Mic: ON" in the panel — viewers hear game audio
   only. Tap again — your mic is back.
6. **Mix.** Drag the Game slider to 0 — viewers hear mic only. Drag it
   back to 100 — the mix returns. (Mid-range positions change nothing
   yet; that's the Phase 4 note above, not a bug.)
7. **Stop from the bubble.** Tap STOP in the panel. Stream ends, bubble
   turns grey, status Idle.
8. **Hide.** Notification → Hide, or "Disable floating controls" in the
   app. Bubble and notification disappear.

## Phase 3 — squad voice room

Mesh WebRTC, audio only. Every player holds one `PeerConnection` per other
player (max 3). One rule keeps the mesh glare-free: when two peers learn
of each other, the one with the lower peer id sends the offer; the other
answers. Teammates hear ONLY your mic — never game audio, never their own
voice back. Remote voices play through the phone speaker by default.

Discord-style speaking view: the room lists everyone by name with a live
mic level (WebRTC `getStats` `audioLevel`, polled ~3x/sec). The row of the
person talking gets a green ring, a green dot and a level bar. Your own
row ("You") shows your mic level too. The name is set with the **Save
name** button under the name field — it confirms with a toast.

Signaling is Ably Realtime: one channel per room code, one event
(`signal`) carrying the join/hello/offer/answer/ICE/leave messages as
JSON strings. No tables, no database, no login — the API key is all the
auth there is. The key is baked into the build from a GitHub secret
(see below) — never in code, never in git, never typed into the app.
The paste-in field in the app remains as an override.

Ably replaced Supabase here: Supabase's free tier pauses the project
after 7 days of inactivity, and its Kotlin client (supabase-kt) changed
API in 3.9.0, which broke the first Phase 3 build. Ably's free tier has
no inactivity pause: 6M messages/month, 200 concurrent connections,
500 messages/second, no credit card, no time limit.

WebRTC comes from the maintained `io.github.webrtc-sdk:android`
fork (150.7871.01) — Google's `org.webrtc:google-webrtc` is dead (last
1.0.32006, Aug 2020, JCenter gone) and does not resolve in 2026. Same
`org.webrtc.*` package, so the code looks like stock WebRTC. ICE uses
free public Google STUN (`stun.l.google.com:19302`); there is no TURN
server yet.

### Ably setup (do this once, ~2 minutes)

1. Go to **ably.com** → sign up (email or GitHub; no card) → **Create
   an app** (any name).
2. In the app: **API Keys** → copy the key (it looks like
   `xVLy...:xxx...`). It is the root key — it can do everything in
   that app, so keep it inside the squad. **Do not paste it into any
   chat or file — the next two steps are the only places it goes.**
3. In the GitHub repo: **Settings → Secrets and variables → Actions →
   New repository secret**. Name: `ABLY_API_KEY`. Secret: paste the key.
4. In the repo, open **.github/workflows/android.yml** and add two lines
   to the *Build debug APK* step, so it reads:
   ```yaml
   - name: Build debug APK
     env:
       ABLY_API_KEY: ${{ secrets.ABLY_API_KEY }}
     run: gradle assembleDebug --stacktrace
   ```
5. That's it. The next build bakes the key into the APK — install it
   and the voice room just works, no typing. The key field in the app
   stays as an override: leave it blank to use the built-in key, or
   paste a different one to override it.

If the key ever leaks or needs rotating: regenerate it in the Ably
dashboard, update the GitHub secret, and the next build carries the new
one (clear the paste-in field on any phone that had the old key typed in).

### Room flow

1. One player taps **Create room** — the app makes a 6-character code
   (e.g. `K7Q2XA`) and joins. The code shows on screen: share it in your
   squad chat.
2. The others paste the code and tap **Join room**.
3. Everyone sees the names of who is in. Talk: mic is live, speaker is on.
4. **Mute my mic** stops your voice to the room. **Speaker on/off** moves
   voices between speaker and earpiece. The HUD bubble's **Squad** button
   mutes the squad voices *you* hear (your mic to them keeps working).
5. **Leave room** (or the notification's Leave) ends it for you. The room
   itself has no host — it exists while anyone is in it.

### Phase 3 verification test

1. **Setup.** Do the Ably steps above once — the key is baked into the
   build, so the squad installs the APK and never types a key.
2. **Create.** Tap Create room — a code appears, status "Waiting for
   squad mates…". A "Squad voice • Room CODE" notification appears.
3. **Join (second phone).** Install the same APK, type your name, paste
   the code, Join — both phones list each other by name within a few seconds.
4. **Talk.** Speak on phone A: phone B hears you through the speaker —
   and A's row on B's screen lights up green with a moving level bar.
   Speak on phone B: phone A hears you. No game audio leaks into the
   room (start a stream too and check the YouTube preview still has
   game + mic, not squad voices).
5. **Mute.** Mute my mic on A — B hears nothing from A. Unmute — voice
   is back. HUD Squad button on A — A stops hearing B, B still hears A.
6. **Leave + rejoin.** Leave on B — A sees B disappear. B joins again
   with the same code — the voice link comes back.
7. **Stream + voice together.** Stream from A while in the room with B.
   Both keep working; watch for echo on A's mic (see limits).

Pass = steps 2–6 work between two phones on mobile data (not just Wi-Fi),
with clear voice both ways and no crash in 10 minutes.

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
│   ├── libs.versions.toml         # AGP 8.7.3, Kotlin 2.2.0, Compose BOM, RootEncoder 2.8.2,
│   │                              # webrtc-sdk 150.7871.01, ably-java 1.8.2
│   └── wrapper/gradle-wrapper.properties   # Gradle 8.11
├── .github/workflows/android.yml  # cloud build → debug APK (no PC needed)
└── app/
    ├── build.gradle.kts           # minSdk 29, targetSdk 35, compileSdk 35
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    # permissions + foreground service types
        └── java/com/koseiedits/squadstream/
            ├── MainActivity.kt    # Compose UI: stream + HUD toggle + voice room
            ├── StreamService.kt   # foreground service: capture → encode → RTMP
            ├── HudService.kt      # Phase 2: floating bubble + control panel
            ├── VoiceService.kt    # Phase 3: foreground service hosting the voice room
            ├── VoiceRoomManager.kt # Phase 3: WebRTC mesh (offers, ICE, remote audio)
            ├── AblySignaling.kt   # Phase 3: Ably Realtime signaling
            ├── VoiceModels.kt     # Phase 3: signal messages + room codes
            ├── StreamConfig.kt    # quality presets + audio modes
            └── StreamState.kt     # service → UI status flows (incl. mix gains + voice)
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
   your phone screen within ~30 s. The small `● LIVE SQUADSTREAM`
   watermark fades in and out in the top-left corner.
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
| Bubble never appears | Overlay permission off | App → Floating controls → "Allow display over other apps" → turn SquadStream on |
| Bubble disappears when idle | Android killed the HUD service | Reopen the app (it restarts the bubble if enabled); while streaming it stays alive |
| Mic mute also kills game audio | — | Should not happen: mute swaps to game-only source. If it does, report it — that's a bug, not the design |
| Sliders do nothing mid-range | By design until Phase 4 | Only 0 switches a source off; true volume gain needs the Phase 4 mixer |
| Room never shows mates | Wrong API key, or no network | Re-copy the key from Ably dashboard → your app → API Keys; check mobile data |
| Voice connects on Wi-Fi but not mobile data | Symmetric NAT / carrier NAT | Free STUN only — two players behind strict NATs may fail. A TURN server (Phase 4 hardening) fixes it |
| Echo while streaming + voice together | Mic captured twice (stream + WebRTC) | Lower the speaker volume or use earphones; the echo cancelers usually cope, but two captures can fight on some phones |
| Crash on Start (Android 14) | Foreground service type | Already handled: manifest declares `mediaProjection\|microphone` and the service passes both to `startForeground()` |
| Badge missing, stream fine | Overlay failed | Cosmetic only — the overlay is best-effort by design; stream is unaffected |

## Known limits (honest)

- **Rotation:** Phase 1 encodes landscape 1280×720, rotation 0. If you
  start the stream from a portrait screen, the image is scaled, not
  rotated. A rotation-aware mode comes with the Phase 2 HUD work.
- **No squad voice in the stream yet:** the voice room is separate —
  teammates hear each other, viewers hear game + your mic. Mixing squad
  voices into the stream is Phase 4.
- **Mix sliders are on/off, not volume:** Phase 2 swaps audio sources;
  fine-grained per-channel gain needs the Phase 4 custom mixer.
- **Bigger APK:** WebRTC ships native code — the debug APK grows from
  ~14 MB to roughly 30 MB. That is the price of the voice room; there is
  no lighter WebRTC.
- **Upload is the bottleneck:** 720p30 at 4 Mbps needs a stable ~5 Mbps
  upload. If your mobile data is slow, drop to the adaptive floor or
  stream on Wi-Fi.

## Next: Phase 4

Custom 3-channel PCM mixer: mic (VOICE_COMMUNICATION + AEC/NS) +
internal game audio (AudioPlaybackCapture) + WebRTC squad voices
(intercepted from the WebRTC audio device module) into one 44.1/48 kHz
PCM buffer for RootEncoder's AAC encoder — per-channel gain sliders,
soft-clipping/limiter. Teammates hear only your mic; viewers hear all
three mixed.
