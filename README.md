# SongSync (Kotlin)

Play the same song on several Android phones at once, in sync, so they sound like one speaker
system. One phone hosts a group and picks music from JioSaavn or YouTube; nearby phones join over
Google Nearby Connections (Bluetooth / Wi-Fi, no shared network needed) and every phone streams
the track itself.

This is a native Kotlin rewrite of the Flutter app [SirEthic/SongSync](https://github.com/SirEthic/SongSync).

## What changed compared with the Flutter app

| Problem in the original | What the rewrite does |
|---|---|
| Sync used relative `Future.delayed` timers on the UI thread and RTT/2 averaged over 5 pings with the wall clock | NTP-style clock-offset estimation on the monotonic clock, filtered to the fastest round trips |
| No drift correction once playing; a rebuffer meant permanent echo | Closed loop every 100 ms: tiny speed nudges for small drift, re-sync for large drift |
| Device start latency only fixable by a manual slider that wasn't saved | Start latency is learned per phone and per audio output and persisted; manual calibration is kept and saved too |
| UI jank: whole screen rebuilt on every ping, hidden player rebuilt constantly and crashing on null, seek on every drag tick | Compose with isolated position polling (4 Hz, only where shown), seek on release |
| Only one client; host stopped advertising after the first join; auto-joined the first host seen | Any number of phones, pick-a-host list, phones can join or rejoin at any time |
| No reconnect | Clients keep playing on the last known timeline and reconnect automatically for 60 s |
| Stopped in the background | Foreground media session service: screen off, lock-screen controls, headset buttons |
| YouTube stream URLs (tied to the host's IP) were shared with clients | Each phone resolves the track itself; the host shares only IDs |
| Always requested JioSaavn 320 kbps, which 404s for some songs | Verified 320 → 160 → 96 kbps fallback; host picks, every phone fetches the same file |
| Nearby default connection type could move phones onto a hotspot, cutting their internet | `NON_DISRUPTIVE` connections keep every phone on its own network |

## How sync works

1. **Shared timeline.** The host publishes `PlaybackState {seq, track, playing, anchorHostTime, anchorPosition}`
   on every change and once a second. Any phone can compute where the song *should* be at any instant,
   so lost messages, late joins and reconnects all heal by applying the latest state.
2. **Clock offset.** Clients exchange timestamps with the host (16 at once on connect, then 1/s) and keep
   the median offset of the fastest quarter of recent exchanges (the window widens on jittery links).
3. **Scheduled actions.** Play/pause/seek take effect at a host time a few hundred ms ahead (based on the
   measured link latency). Each phone pauses, pre-positions, and calls `play()` early by its learned start
   latency so audio comes out on time; pauses happen on the same sample everywhere.
4. **Closed loop.** While playing, each phone compares ExoPlayer's position (derived from AudioTrack
   timestamps, i.e. what is being heard) with the timeline: under 4 ms nothing happens, up to 120 ms it
   adjusts playback speed by up to ±2 %, beyond that it re-syncs.

In the whole-stack simulation (random clock offsets of ±200 ms, ±50 ppm clock drift, 40–150 ms audio
start latency, jittery network with spikes) every phone stays within **5 ms** of the timeline
(observed 2.9–4.8 ms). Real-world numbers depend on the phones; the in-app diagnostics show them.

## Building

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37 (`compileSdk`; the app targets 36).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or open the project in Android Studio
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # unit + simulation tests
./gradlew lintDebug
./gradlew assembleRelease          # R8-minified, ~4 MB
```

### Release signing

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=release.jks
storePassword=...
keyAlias=songsync
keyPassword=...
```

Without it, release builds are signed with the debug key so they still install locally.

### Baseline profile (optional, needs a phone)

With a phone connected over adb: `./gradlew :app:generateReleaseBaselineProfile`, then commit the
generated `app/src/release/generated/baselineProfiles/`. Makes the first launch smoother.

## Tests

- `sync/` – clock sync, timeline, drift controller, and `SyncSimulationTest`, which runs a host and
  several clients with simulated clocks, audio hardware and network, asserting on what a listener would hear:
  convergence, pause/seek/resume, a late joiner, a dropped link, queue auto-advance.
- `data/` – JioSaavn decryption (FIPS DES vector + a real payload), parsing, click track, downloader cancellation.
- `net/` – protocol round trips, endpoint info.
- Live smoke tests against the real services are opt-in: `./gradlew testDebugUnitTest -PliveTests`.

### On real phones

Nearby Connections does not work in the emulator, so final checks need two or more phones:

1. Host on one phone, join from the list on the others. Play, pause, seek, skip.
2. Settings → Sync diagnostics: the sync error should stay within about ±10 ms.
3. Host menu → **Play sync test** (a generated click track). Record the phones with a laptop mic and measure
   the gap between clicks in Audacity: the target is ≤ 20 ms (inaudible as echo). Use the echo calibration
   slider for phones or Bluetooth speakers that remain early/late.
4. Lock the screens for 10 minutes: playback continues.
5. Turn Wi-Fi/Bluetooth off and on on a client: it keeps playing, rejoins, and re-syncs.
6. Join mid-song; take a phone call on a client (it pauses and re-syncs afterwards).
7. Jank: `adb shell dumpsys gfxinfo com.songsync.app` while scrolling results and with the player open.

## Project layout

```
app/src/main/java/com/songsync/app/
  data/       Track model, JioSaavn + YouTube sources, search cache, settings, click track
  net/        Wire protocol (kotlinx.serialization), Transport interface, Nearby implementation
  sync/       Timeline, ClockSync, DriftController, PlaybackFollower, host/client coordinators (pure Kotlin)
  playback/   ExoPlayer engine, precise scheduler, audio routes + latency profiles, media session service
  session/    SessionManager: discovery, hosting/joining, reconnect, recovery
  ui/         Compose screens and the ViewModel
baselineprofile/  Baseline profile generator
```

## Notes

- JioSaavn and YouTube are used through unofficial interfaces, which can change or break at any time;
  each source sits behind `MusicSource`. YouTube extraction is not allowed on Google Play, so distribute
  the APK directly (e.g. GitHub Releases).
- Bluetooth speakers and headphones add latency that phones report inconsistently; that is what the
  per-output calibration is for.
- Nearby works best when it can use Wi-Fi; on Bluetooth-only links the clock estimate is noisier.

## License

GPL-3.0-or-later (see `LICENSE`), because the app includes NewPipe Extractor. The original Flutter app is
MIT-licensed by SirEthic; its notice is reproduced in `NOTICE` together with third-party credits.
