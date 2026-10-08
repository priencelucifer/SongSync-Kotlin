# SongSync (Kotlin)

Play the same song on several Android phones at once, in sync, so they sound like one speaker
system. One phone hosts a group and picks music from JioSaavn or YouTube; nearby phones join over
Google Nearby Connections (Bluetooth / Wi-Fi, no shared network needed) and every phone streams
the track itself.

This is a native Kotlin rewrite of an earlier Flutter version of SongSync.

## What changed compared with the Flutter app

| Problem in the original | What the rewrite does |
|---|---|
| Sync used relative `Future.delayed` timers on the UI thread and RTT/2 averaged over 5 pings with the wall clock | NTP-style clock-offset estimation on the monotonic clock, filtered to the fastest round trips |
| No drift correction once playing; a rebuffer meant permanent echo | Closed loop every 100 ms: tiny speed nudges for small drift, re-sync for large drift |
| Device start latency only fixable by a manual slider that wasn't saved | Start latency is learned per phone and per audio output; the remaining speaker/Bluetooth delay is measured acoustically by **Auto-calibrate echo** |
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

5. **Auto-calibrate echo.** Some delay happens after the audio leaves the app (speaker DSP, Bluetooth)
   and phones often misreport it. On the host's request every phone plays a chirp track in sync, but each
   phone is only audible in its own slot; the host's microphone records the run, a matched filter finds
   each chirp's arrival to a fraction of a millisecond (first arrival, so reflections don't fool it), and
   each phone's measured lateness becomes its correction, saved per speaker/headphones. Timing corrections
   freeze while chirps play. The recording stays in memory for ~15 s and is never stored or sent. A manual
   fine-tune remains under Settings → Echo calibration (advanced).

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
- `calibration/` – chirp detection and per-phone offsets from synthetic recordings (mic delay, loudness,
  louder-than-direct reflections, noise, unheard phones): offsets recovered within 0.3 ms.
- `data/` – JioSaavn decryption (FIPS DES vector + a real payload), parsing, click track, downloader cancellation.
- `net/` – protocol round trips, endpoint info.
- Live smoke tests against the real services are opt-in: `./gradlew testDebugUnitTest -PliveTests`.

### On real phones

Nearby Connections does not work in the emulator, so final checks need two or more phones:

1. Host on one phone, join from the list on the others. Play, pause, seek, skip.
2. Settings → Sync diagnostics: the sync error should stay within about ±10 ms.
3. Host → **Auto-calibrate echo** (phones in place, volume up, room quiet), then **Check sync**: it measures
   each phone against the host with the microphone and changes nothing. Targets: spread ≤ 2 ms excellent,
   ≤ 5 ms good, ≤ 10 ms acceptable; above about 8 ms an echo becomes audible on sharp sounds.
4. Lock the screens for 10 minutes: playback continues.
5. Turn Wi-Fi/Bluetooth off and on on a client: it keeps playing, rejoins, and re-syncs.
6. Join mid-song; take a phone call on a client (it pauses and re-syncs afterwards).
7. Jank: `adb shell dumpsys gfxinfo com.songsync.app` while scrolling results and with the player open.

### Independent sync measurement (external recording)

An outside check on **Check sync** that also shows slow drift over a whole song:

1. Put the phones in a row, each about 10 cm from a laptop (or a spare phone) microphone, all at the same
   distance. Sound travels about 2.9 ms per metre, so unequal distances show up as fake offsets.
2. Host → **Play sync test** (click track), volume up on every phone, room quiet.
3. Record 5 minutes (Audacity, 48 kHz).
4. Zoom in on one click at 0:30, 2:30 and 4:30 and note the time between the first and last onset of that
   click. That is the spread.
5. Swap the phones' positions and repeat: an offset that follows the phone is the phone's; one that stays
   with the position is geometry.

Three runs should agree within about 0.5 ms. A spread that grows from 0:30 to 4:30 means drift is not being
corrected (phone clocks differ by up to about 80 ppm, which is about 5 ms per minute uncorrected).

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
- If a phone does not see a group, turn its Bluetooth off and on and search again. Android 8 throttles
  Bluetooth scanning once Play services has scanned for a long time (`dumpsys bluetooth_manager` then shows
  its scanner as "Forced-Opportunistic" with 0 results); toggling Bluetooth resets it.
- The app asks for precise location on every Android version: Google's documentation says it is not needed
  on Android 13+, but Play services' discovery fails with `MISSING_PERMISSION_ACCESS_FINE_LOCATION` (8036) /
  `..._COARSE_LOCATION` (8034) without it (hosting is unaffected). The app never reads the location. Nearby
  errors shown in the app include the status code and a shortcut to the right settings screen.

## License

GPL-3.0-or-later (see `LICENSE`), because the app includes NewPipe Extractor. The original Flutter app is
MIT-licensed; its notice is reproduced in `NOTICE` together with third-party credits.
