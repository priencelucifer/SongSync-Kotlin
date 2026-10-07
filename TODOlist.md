# SongSync: plan for tighter sync

Working plan for future Claude Code sessions. It is derived from
[the research report](reports/Android%20multi%20phone%20audio%20sync.md) (cited below as "R §…") and checked against
the code as of v2.1.0 (versionCode 5). Read `SongSync-HANDOVER.md` first, and follow its conventions: one commit per
change, run tests and lint before committing, bump the version for every APK the user installs, and push only when asked.

## Targets (device-to-device acoustic emission error, measured with a microphone, not with `currentPosition`)

| Tier | Error | Meaning (R §[Ears set the target](reports/Android%20multi%20phone%20audio%20sync.md#ears-set-the-target-at-1-to-5-ms-and-geometry-sets-the-floor)) |
|---|---|---|
| Excellent | ≤ 1–2 ms | One fused image (summing localization) |
| Good | ≤ 5 ms | Fused; some comb coloration |
| Acceptable | ≤ 10 ms | Fused for music; the image pulls toward the nearest phone |
| Bad | > 20 ms | Audible doubling or echo on transients (equal-level echo threshold is about 7–8 ms) |

- **Status (2.3.0):** Phase 0 and 1 done, plus a one-tap "Run full sync test" report (host menu). Sim: worst 4.21 ms over 6 seeds, BT-like 6.7 ms. Waiting for the first real-phone report.
- **Plan goal: ≤ 2 ms p95 on Wi-Fi with built-in speakers, ≤ 5 ms p95 worst case on Wi-Fi.** Bluetooth outputs are a separate, best-effort mode.
- Floor: 2.9 ms per metre of path difference. Below about 1 ms, gains are audible only near the point where all phones are equidistant.
- Today: the sim reports ≤ 4.4 ms steady state, but `SimulatedPlayer` gives an *unbiased* reported position (true + ±1.5 ms noise). Real-phone acoustic error is **unknown**, which is why Phase 0 comes first.

## What already exists (do not re-implement)

- Shared timeline (`sync/Timeline.kt`), scheduled starts with learned start latency per route (`PlaybackFollower.arm`, `RouteLatencyProfile`), shared pause instant.
- `ClockSync`: median offset of the fastest 25% of samples, adaptive window 20/60, receive timestamps taken first thing in `NearbyTransport.onPayloadReceived`.
- `DriftController`: dead-band 4 ms, hysteresis to 1.5 ms, Sonic speed (pitch preserved) quantised to 0.25%, max ±2%, dwell 1.5 s, hard re-sync at > 150 ms for 2 s with back-off.
- JioSaavn bitrate pinned by the host (`Track.bitrateKbps`, `JioSaavnSource.resolve`).
- Echo calibration: `CalibrationSignal` (48 kHz WAV, 2–8 kHz, 30 ms, 4 chirps per slot), `ChirpDetector` (FFT matched filter, analytic envelope, first arrival at 50%), `CalibrationAnalyzer` (mic delay from the host's chirps, median of ≥ 3 chirps, spread ≤ 5 ms, SNR ≥ 6), `MicRecorder` (UNPROCESSED, else VOICE_RECOGNITION; AudioRecord timestamp). Corrections are relative to the group median and also subtract each phone's reported tracking error (`SessionManager` around line 365).
- Diagnostics panel (`ui/components/Components.kt` `DiagnosticsPanel`, `SessionManager.SyncStats` and `syncLog`).

---

## Phase 0: Measure first (find where the delay actually comes from on real phones)

- [x] **0.1 "Check sync" (measure-only acoustic test)**
  - What: a host action that runs the existing calibration flow but **applies nothing**. Instead it shows each phone's residual lateness in ms, relative to the host, in a result dialog and in the sync log.
    - Note (verified in code): `autoCalibrate()` replaces the current song with the 48 kHz calibration WAV (`playNow`, `SessionManager.kt:346`) and pauses in its `finally`. So Check sync measures the calibration-WAV path after a fresh scheduled start, not steady music. Remember the song and position first and restore them afterwards.
    - Optional variant: keep all phones audible and run a click track (`ClickTrack`) instead of slots, to measure the *combined* sound.
  - Files: `session/SessionManager.kt` (calibration orchestration; add a `measureOnly` flag that skips `latency.setCalibration` and `CalibrationResult`), `ui/components/Calibration.kt`, `ui/session/HostScreen.kt` menu, `calibration/CalibrationAnalyzer.kt` (unchanged; reuse `Result.phones[].lateMs`).
  - Why: this gives acoustic ground truth. Every later task is judged by this number, not by `follower.errorMs` (R §[ExoPlayer hides the timestamps](reports/Android%20multi%20phone%20audio%20sync.md#exoplayer-hides-the-timestamps-that-sample-accurate-sync-needs)).
  - Verify: unit test in `CalibrationAnalyzerTest`: a measure-only result equals the analyze result and no correction is produced. Phone test: 3 phones side by side, run Check sync 3 times, record the per-phone ms values. Expect a repeatability of ±0.5 ms; if it is worse, the measurement itself needs work before tuning anything.
  - Effort: M

- [x] **0.2 Log what each phone is actually playing**
  - What: on `Player.Listener.onTracksChanged`, read the selected audio `Format` (`sampleMimeType`, `codecs`, `sampleRate`, `channelCount`, `encoderDelay`, `encoderPadding`, `bitrate`). Get the decoder name from `AnalyticsListener.onAudioDecoderInitialized`. For YouTube, also log the chosen itag and content length (from `YouTubeSource.audioUrl`; NewPipe `AudioStream.itag`). Show these in the diagnostics panel. Send a compact summary to the host (optional field in `TrackReady` or `ClientStatus`; optional fields are wire-compatible because `ignoreUnknownKeys = true`), and have the host flag phones whose format differs.
  - Files: `playback/PlayerEngine.kt`, `data/source/YouTubeSource.kt`, `net/Protocol.kt`, `session/SessionManager.kt` (`SyncStats`), `ui/components/Components.kt`.
  - Why: different files carry different priming: AAC 2112 samples = 47.9 ms, HE-AAC 5186 = 118 ms, Opus 312 = 6.5 ms. YouTube itags 140 and 251 are not time-aligned (R §[Different files](reports/Android%20multi%20phone%20audio%20sync.md#different-files-can-hide-6-to-118-ms-of-constant-offset)).
  - Verify: `ProtocolTest` round-trips the new optional fields and decodes old messages without them. Phone test: play 5 YouTube songs on 3 phones and compare the itag, encoderDelay and sampleRate rows.
  - Effort: S

- [x] **0.3 Clock-quality stats**
  - What: extend `ClockSync.Estimate` with the spread of the best-quarter offsets (MAD or IQR, in µs) and the age of the newest sample used. Show min/median/p90 RTT, offset spread, link quality (`BandwidthChanged.quality`), and the offset change per minute (an apparent skew in ppm). Log `clock offset jump` events with their size.
  - Files: `sync/ClockSync.kt`, `sync/ClientCoordinator.kt`, `session/SessionManager.kt`, `ui/components/Components.kt`.
  - Why: the expected accuracy is ±1 ms over Wi-Fi and ±3–5 ms over BLE; Wi-Fi power save adds ≥ 100 ms of *asymmetric* delay (R §[Phone clocks](reports/Android%20multi%20phone%20audio%20sync.md#phone-clocks-sync-to-about-1-ms-over-wi-fi)).
  - Verify: `ClockSyncTest` checks the spread on synthetic samples with known jitter. Phone test: screenshot the diagnostics on Wi-Fi LAN and on a Bluetooth-only link (Wi-Fi off).
  - Effort: S

- [x] **0.4 External recording protocol (no code)**
  - What: write down a repeatable test in the README or handover:
    1. Play the `CLICK_TEST` click track on all phones placed in a row about 10 cm from a laptop or third-phone mic.
    2. Record 5 minutes.
    3. In Audacity, measure the click-onset spread at 0:30, 2:30 and 4:30.
    4. Repeat with the phones swapped in position to separate device offset from geometry.
  - Why: this is an independent check on 0.1, and it shows slow drift over a whole song (1–80 ppm clocks give up to about 9.6 ms/min if uncorrected).
  - Verify: three runs give consistent numbers (±0.5 ms).
  - Effort: S

- [x] **0.5 Make the simulator honest about bias**
  - What: in `SimulatedPlayer` (`app/src/test/.../sync/sim/SimWorld.kt`), add a per-phone `reportBiasMs`: a constant difference between `positionMs` and what is heard (`positionMs = heard + reportBiasMs`), standing in for unreported output delay or a priming mismatch. `positionMs` is already rounded to whole ms; add an optional smoothing that mimics `currentPosition`. Add a sim test asserting that bias is invisible to the follower without calibration and removed after a simulated calibration. The follower's error is `positionMs − (timeline + calibrationMs)` (`PlaybackFollower.kt:371`), so cancelling needs `InMemoryLatencyProfile.calibrationMs = +reportBiasMs`.
  - Why: the current "≤ 4.4 ms" is measured against an unbiased reported position, so it is a lower bound on the real error.
  - Verify: the new `SyncSimulationTest` case, run with `./gradlew testDebugUnitTest`.
  - Effort: S

**Open questions Phase 0 must answer on real phones:**

1. What is the acoustic spread after lock, with and without the current calibration?
2. Do phones get different YouTube itags or formats for the same video?
3. Do calibration corrections differ between the 48 kHz WAV path and a 44.1 kHz music path? (Measure by adding a 44.1 kHz variant of the calibration WAV.)
4. How many phones lack UNPROCESSED, and what SNR does VOICE_RECOGNITION give on 4–8 kHz?
5. Do `AudioTrack` timestamps advance on each phone's speaker and Bluetooth routes? (Answered in 2.1.)
6. What are the RTT and offset spread on Nearby Wi-Fi LAN versus Bluetooth?

---

## Phase 1: Quick wins (small changes, likely large effect)

- [x] **1.1 Pin the YouTube stream (itag) like the JioSaavn bitrate**
  - What: add an optional `itag: Int?` (and optionally `contentLength: Long?`) to `Track`. The host fills it in `YouTubeSource.resolve`. When it is set, clients must pick exactly that itag and fail with `TrackFailed` rather than fall back. Prefer one family consistently, for example 140 (AAC 44.1 kHz) or 251 (Opus 48 kHz), and decide which by testing. Bump `PROTOCOL_VERSION` to 3, because old clients would silently ignore the pin; note that a mismatch *rejects* old phones (`RejectReason.PROTOCOL_MISMATCH`), which is fine because every phone installs the same APK. No extra plumbing is needed: the host already broadcasts `resolved.track` (`HostCoordinator.kt:130-134`), so `track.copy(itag = …)` reaches clients just like `bitrateKbps`.
  - Files: `data/model/Track.kt`, `data/source/YouTubeSource.kt` (`audioUrl`), `net/Protocol.kt`, `sync/ClientCoordinator.kt` (no change to `allowAlternatives = false`).
  - Why: each phone currently picks the highest `averageBitrate` stream independently. 140 and 251 differ in alignment by 6.5–60 ms, and that offset is invisible to the loop (R §[Different files](reports/Android%20multi%20phone%20audio%20sync.md#different-files-can-hide-6-to-118-ms-of-constant-offset)).
  - Verify: a unit test for a stream-selection helper (pinned itag chosen; missing itag gives an error). A `LiveSourcesTest` (`-PliveTests`) case that resolves the same video twice gets the same itag. Phone test: the 0.2 rows match on all phones, and Check sync (0.1) shows no ≥ 5 ms constant offset that appears only on YouTube.
  - Effort: S

- [x] **1.2 Stop baking speaker-to-mic distance into calibration**
  - What: the short-term fix is UX. The calibration dialog tells users to place all phones within about 30 cm of the host, or at equal distance from it, during calibration. Show a warning in the results when the corrections spread more than about 6 ms (about 2 m). Do not persist a correction larger than ±150 ms without confirmation.
  - Files: `ui/components/Calibration.kt`, strings, `session/SessionManager.kt`.
  - Why: the host mic hears flight time at 2.9 ms per metre. A phone 2 m away gets corrected about 5.8 ms early, and that is saved per route in `calibration:<route>` and reused after the phones move (R §[Acoustic calibration](reports/Android%20multi%20phone%20audio%20sync.md#acoustic-calibration-fixes-the-last-mile-but-measures-geometry-too)). The long-term fix is 3.3.
  - Verify: phone test. Calibrate with the phones 2 m apart, then repeat with the phones together. The corrections should differ by about 2.9 ms per metre, which confirms the bias. With the new instructions, Check sync at the listening spot should be ≤ 2 ms.
  - Effort: S

- [x] **1.3 Wi-Fi low-latency lock during a session**
  - What: in `SyncPlaybackService` `onCreate` and `onDestroy`, acquire and release a `WifiManager.WifiLock` for the whole session, including while paused. Use `WIFI_MODE_FULL_LOW_LATENCY` on API 29+ and `WIFI_MODE_FULL_HIGH_PERF` below that. Log in diagnostics whether the lock is held.
    - Already present: `WAKE_LOCK`, `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` in the manifest, and ExoPlayer's `setWakeMode(C.WAKE_MODE_NETWORK)` (`PlayerEngine.kt:72`), which holds a Wi-Fi lock only *while playing* (not low-latency mode).
  - Files: `playback/SyncPlaybackService.kt`.
  - Why: 802.11 power save defers downlink frames by one or more 100 ms beacons, which breaks NTP symmetry. Low-latency mode disables power save while the app is in the foreground with the screen on and Wi-Fi has internet, which holds for SongSync because every phone streams (R §[Phone clocks](reports/Android%20multi%20phone%20audio%20sync.md#phone-clocks-sync-to-about-1-ms-over-wi-fi)).
  - Verify: phone test. Compare the 0.3 stats (min/p90 RTT, offset spread) over 5 minutes with and without the lock, screen on and screen off. Expect a lower p90 RTT and spread.
  - Effort: S

- [x] **1.4 Drop stale clock samples when the Nearby medium changes**
  - What: on `TransportEvent.BandwidthChanged`, `ClientCoordinator` currently fires only `timeSyncBurst()`. Instead, keep the current estimate, but once the burst has produced at least `minClockSamples` new samples, recompute from post-change samples only. Add `ClockSync.markEpoch()` to discard older samples lazily, so the estimate is never empty. The follower already slews changes under 150 ms (`offsetSlewThresholdMs`).
  - Files: `sync/ClockSync.kt`, `sync/ClientCoordinator.kt`.
  - Why: the BT→Wi-Fi upgrade changes the delay structure. Fastest-quarter filtering over a mixed window can keep biased BT samples for up to 60 s (R §[Phone clocks](reports/Android%20multi%20phone%20audio%20sync.md#phone-clocks-sync-to-about-1-ms-over-wi-fi)).
  - Note: `minClockSamples` (4) lives in `SessionConfig` (`sync/LocalPlayback.kt:35`), not `SyncConfig`. `FakeNetwork` takes its `LatencyModel` in the constructor and never emits `BandwidthChanged`, so the sim needs a `setLatency()` helper and a way to emit that event.
  - Verify: a `ClockSyncTest` case where samples with a 3 ms asymmetric bias are followed by clean samples after `markEpoch()` converges to the true offset within 4 samples. A sim test: switch the `LatencyModel` mid-run and emit `BandwidthChanged`.
  - Effort: S

- [x] **1.5 Gentler, finer speed correction**
  - What: in `SyncConfig`, set `speedStep` 0.0025 → 0.001. Add `smallErrorMaxNudge = 0.005` (±0.5%) for |error| < 20 ms, and keep `maxSpeedNudge = 0.02` only for catch-up beyond 20 ms. Leave `deadbandMs` at 4.0 until 2.1 lands. Re-check `minSpeedDwellMs` (1.5 s): each Sonic change creates a position checkpoint.
  - Files: `sync/SyncConfig.kt`, `sync/DriftController.kt`, `DriftControllerTest.kt`.
  - Why: real systems correct at ≤ 0.05% (Snapcast) with a 2 ms dead-band (AirPlay). Coarse 0.25% steps make the loop overshoot around a 4 ms band (R §[Every accurate system](reports/Android%20multi%20phone%20audio%20sync.md#every-accurate-system-timestamps-audio-against-one-clock)).
  - Verify: the `DriftControllerTest` mapping table is updated. All `SyncSimulationTest` cases still pass, and the steady-state and "BT-like 7.9 ms" figures are no worse; print the number of speed changes per minute and expect fewer. Phone test: listen for time-stretch artefacts at 0.5% versus 2% on a sustained piano or vocal track.
  - Effort: S

- [x] **1.6 Bluetooth route = separate, honest mode**
  - What: when `AudioRouteMonitor.route.type == BLUETOOTH`, show a "Bluetooth output: sync is approximate, run echo calibration" chip in the sync UI and on the host's device list (send the route type in `ClientStatus`). Prompt to re-calibrate when the route changes; this is already a handover idea. (2.3.0: device-list mark and player hint done; an automatic prompt on route change is not.)
  - Files: `playback/AudioRouteMonitor.kt`, `net/Protocol.kt`, `ui/session/HostScreen.kt`, `ui/components/Components.kt`.
  - Why: A2DP adds 100–300 ms, and delay reports are only as good as the headset's AVDTP report (R §[ExoPlayer hides the timestamps](reports/Android%20multi%20phone%20audio%20sync.md#exoplayer-hides-the-timestamps-that-sample-accurate-sync-needs)).
  - Verify: `ProtocolTest` covers the new field. Phone test: connect BT earbuds and check that the chip appears and the host sees it.
  - Effort: S

---

## Phase 2: Medium (better measurement and clock model, still on ExoPlayer)

- [ ] **2.1 Microsecond, timestamp-derived position from the audio sink**
  - What:
    1. Build the ExoPlayer with a custom `DefaultRenderersFactory.buildAudioSink(...)` that returns a `ForwardingAudioSink(DefaultAudioSink)`.
    2. Override `getCurrentPositionUs(sourceEnded)` to store `(positionUs, System.nanoTime())` atomically on every call. The playback thread calls it about every 10 ms.
    3. Expose `positionAtUs(nowElapsedNs)`, which extrapolates from the latest pair times the current speed. Convert MONOTONIC to `elapsedRealtimeNanos` with a back-to-back paired reading, as `MicRecorder` already does.
    4. Use it in `SyncPlayer.positionMs`; change the interface to a `Double` ms or µs position.
    5. Also record whether Media3 is in timestamp mode (advancing timestamps) if that is observable, and log it.
    6. Once Check sync (0.1) shows that the measurement agrees with the acoustics, lower `deadbandMs` 4 → 2 and `correctionDoneMs` 1.5 → 0.5, and consider `settleMs` 1000 → 600.
  - Files: `playback/PlayerEngine.kt`, `sync/PlaybackFollower.kt` (`SyncPlayer`), `sync/SyncConfig.kt`, the sim player.
  - Why: `Player.currentPosition` is ms-quantised, smoothed and read on the main thread, so the loop cannot see errors below about 2–4 ms. Media3 positions already come from `AudioTrack.getTimestamp`, which the CDD requires to be within ±2 ms and recommends within ±1 ms (R §[ExoPlayer hides the timestamps](reports/Android%20multi%20phone%20audio%20sync.md#exoplayer-hides-the-timestamps-that-sample-accurate-sync-needs)). This is an inference, so check it with 0.1.
  - Verify: a sim test with the quantised and smoothed reporting from 0.5 against µs reporting should show a lower steady-state error. Phone test: the diagnostics show error jitter (SD of 10 samples) before and after; expect < 0.5 ms; Check sync p95 ≤ 2 ms.
  - Effort: M

- [ ] **2.2 Offset + skew clock estimator**
  - What: keep the min-RTT filter, then fit `offset = a + b·t` (least squares or a 2-state Kalman filter) over the fastest-quarter samples of a longer window (60–120 s). Report the offset at "now" (not the window midpoint), skew `b` in ppm, and an error bound (≈ minRtt/2 plus residual SD). Rate-limit offset changes as today.
  - Files: `sync/ClockSync.kt`, `ClockSyncTest.kt`, `sync/ClientCoordinator.kt`.
  - Why: phone clocks skew 1–80 ppm. A 20-sample, 1 Hz median lags about 10 s, which at 80 ppm is up to about 0.8 ms of bias, and it needs a short window that adds noise (R §[Phone clocks](reports/Android%20multi%20phone%20audio%20sync.md#phone-clocks-sync-to-about-1-ms-over-wi-fi)).
  - Verify: `ClockSyncTest` with a ±50 ppm drift and exponential jitter: |error| < 0.3 ms after 60 s, against the current estimator's error. The sim's `DeviceClock` already has ±50 ppm, so steady-state error should not regress. Phone test: the 0.3 skew-ppm readout is stable within ±2 ppm.
  - Effort: M

- [ ] **2.3 Content-offset safety net**
  - What: when 0.2 reports a format mismatch that cannot be avoided (for example a JioSaavn tier the host verified but a guest decodes differently, or a different decoder), apply `delta = encoderDelayA/srA − encoderDelayB/srB` as a constant offset added to `calibrationMs` for that track only, as a new `trackOffsetMs` in `LatencyProfile`. If the user wants a stronger version, decode the first 5 s offline and cross-correlate the onset envelope against the host's fingerprint, which is sent once over Nearby.
  - Files: `sync/PlaybackFollower.kt` (`LatencyProfile`), `playback/PlayerEngine.kt`, `net/Protocol.kt`.
  - Why: an untrimmed priming difference is 6.5–118 ms of constant offset, and the loop reports it as zero (R §[Different files](reports/Android%20multi%20phone%20audio%20sync.md#different-files-can-hide-6-to-118-ms-of-constant-offset)).
  - Verify: unit test for the delta arithmetic, and a sim test with a `reportBiasMs` equal to the delta that gets cancelled. Phone test: force different JioSaavn bitrates on two phones (debug flag) and check that Check sync stays ≤ 2 ms.
  - Effort: M. Only do this if Phase 0 finds mismatches after 1.1.

- [ ] **2.4 Calibration robustness on real phones**
  - What:
    - Log the mic source used (UNPROCESSED or VOICE_RECOGNITION), and the per-chirp SNR and spread for each phone.
    - If VOICE_RECOGNITION gives low SNR, try a 2–4 kHz chirp variant, because its flat response is only recommended over 100 Hz–4 kHz.
    - Make sure slot gaps (currently 400 ms chirp spacing) exceed the room reverb tail.
    - Add a 44.1 kHz calibration WAV option so the music's output path is measured (open question 3).
  - Files: `calibration/CalibrationSignal.kt`, `calibration/MicRecorder.kt`, `calibration/CalibrationAnalyzer.kt`, `CalibrationAnalyzerTest.kt`.
  - Why: R §[Acoustic calibration](reports/Android%20multi%20phone%20audio%20sync.md#acoustic-calibration-fixes-the-last-mile-but-measures-geometry-too).
  - Verify: synthetic tests with a band-limited (≤ 4 kHz) mic response and with heavy reverb still give ±0.3 ms. Phone test: in a reverberant room, repeated runs agree within 1 ms.
  - Effort: M

---

## Phase 3: Big rewrites (only after Phase 0–2 numbers justify them)

- [ ] **3.1 Sample-accurate PCM output path (AudioTrack first, Oboe optional)**
  - What: ExoPlayer still handles extraction, decoding and buffering, but the app owns the output.
    - Option A: a custom `AudioSink` that writes to its own `AudioTrack` in `ENCODING_PCM_FLOAT` or 16-bit.
    - Option B: tap decoded PCM and feed a separate AudioTrack or Oboe stream.
    - Core loop, as in Snapcast's `oboe_player.cpp` and `stream.cpp`:
      1. Keep the stream running.
      2. Each callback or write, compute the presentation time of the next frame from `getTimestamp` (frame, nanoTime) plus the queued frames, fitted as a line over several seconds rather than single readings.
      3. Write silence until the frame that maps to the scheduled host time, then audio.
      4. Correct drift by inserting or dropping single frames at ≤ 0.05% (one frame ≈ 20.8 µs at 48 kHz), starting at about 0.1–0.5 ms error.
      5. Hard re-align (silent frame skip or pad, no pause) beyond 2–5 ms.
    - This replaces `learnStartLatency`, Sonic nudges and pause/seek re-syncs for small errors. Keep a fallback to the current ExoPlayer path when timestamps never advance; Snapcast falls back to a 50 ms default latency.
    - Oboe `getTimestamp` is unimplemented on OpenSL ES (API < 27), and minSdk is 26, so start with the Kotlin AudioTrack.
  - Files: new `playback/SyncAudioSink.kt` (or `playback/pcm/…`), `playback/PlayerEngine.kt`, `sync/PlaybackFollower.kt` (new `SyncPlayer` capability: `scheduleStartAt(hostNs, positionUs)`), `sync/SyncConfig.kt` (Snapcast-style thresholds), new sim player model.
  - Why: this is the only proven route to ≤ 1 ms. Snapcast reaches deviation < 0.2 ms with exactly this design (R §[Every accurate system](reports/Android%20multi%20phone%20audio%20sync.md#every-accurate-system-timestamps-audio-against-one-clock), §[ExoPlayer hides the timestamps](reports/Android%20multi%20phone%20audio%20sync.md#exoplayer-hides-the-timestamps-that-sample-accurate-sync-needs)).
  - Verify: JVM unit tests for the frame-scheduling maths (target frame from a timestamp line; insert/drop cadence for a given ppm). A sim test with a frame-level player: steady state ≤ 0.5 ms and no audible gaps. Phone test: Check sync p95 ≤ 1–2 ms on 3 phones; external recording (0.4) shows ≤ 1 ms onset spread over 5 min; no glitches when listening.
  - Effort: L

- [ ] **3.2 Optional host-streaming mode**
  - What: the host decodes once and sends Opus (20 ms frames, 96–128 kbps, 2–3 frames per Nearby BYTES payload < 32 KB) with a sequence number and a host-time presentation stamp. Guests buffer 0.5–2 s and play through the 3.1 sink. Use it for guests without internet, or when 0.2 or 2.3 reports a content mismatch. Use NDK libopus, because the MediaCodec Opus encoder exists only from API 29. Keep `NON_DISRUPTIVE` in mind: bandwidth for N guests is N × 128 kbps over whatever medium Nearby chose. Check licensing before shipping.
  - Files: new `net/AudioStreamProtocol`, `playback/pcm/…`, `session/SessionManager.kt`, `net/NearbyTransport.kt` (payload handling), Protocol v4.
  - Why: identical samples and offline guests. It does **not** fix output latency (R §[Host streaming](reports/Android%20multi%20phone%20audio%20sync.md#host-streaming-buys-identical-samples-at-the-cost-of-the-link)).
  - Verify: sim with packet delay and stall bursts (an extension of `FakeNetwork`): no underruns with a 1 s buffer and 300 ms stalls. Phone test: a guest in airplane mode with Wi-Fi only plays in sync, and Check sync stays ≤ 2 ms.
  - Effort: L

- [ ] **3.3 Two-way acoustic ranging (BeepBeep) to remove geometry from calibration**
  - What: every phone records during calibration, not only the host. Each phone reports the sample counts between its own chirp and the others' chirps. From those, the host computes pairwise distances and subtracts flight time, so phones *emit* simultaneously.
  - Files: `calibration/*`, `net/Protocol.kt` (recordings summary), `session/SessionManager.kt`. Needs RECORD_AUDIO on guests.
  - Why: BeepBeep's self-recording and sample counting cancel OS latency and give cm-level ranging. This removes the 2.9 ms/m bias from 1.2 properly (R §[Acoustic calibration](reports/Android%20multi%20phone%20audio%20sync.md#acoustic-calibration-fixes-the-last-mile-but-measures-geometry-too)).
  - Verify: synthetic multi-recording tests with known distances should recover them within 5 cm. Phone test: calibrate with the phones 2 m apart and get the same emission corrections as with the phones together, within 0.5 ms.
  - Effort: L

---

## Working notes for future sessions

- Run `./gradlew testDebugUnitTest lintDebug` before every commit (55 tests today). Add sim cases under `app/src/test/java/com/songsync/app/sync/sim/`. When a sim test fails, trace it before changing constants (handover §10).
- Any change to wire messages: add the field as optional, add a `ProtocolTest` case, and bump `PROTOCOL_VERSION` when old clients would misbehave (1.1, 3.2).
- Judge every sync change with **Check sync (0.1)** on real phones, and write down the before and after numbers in the commit body.
- Order of attack: 0.1 → 0.2 → 0.3 → 0.5 → 1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 2.1 → 2.2 → (2.3 / 2.4 as Phase 0 dictates) → 3.1 → 3.3 → 3.2.
