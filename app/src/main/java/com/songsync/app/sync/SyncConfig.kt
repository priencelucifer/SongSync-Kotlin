package com.songsync.app.sync

/** Tuning constants for the sync engine. Defaults are starting points to refine on real phones. */
data class SyncConfig(
    /** How often the follower measures and corrects. */
    val tickMs: Long = 100,
    /** Measurements right after a start or seek are unreliable while AudioTrack timestamps settle. */
    val settleMs: Long = 600,
    /** Median window for the drift controller (samples, at [tickMs] each). */
    val windowSamples: Int = 10,
    val minSamplesForDecision: Int = 5,
    /** Below this error nothing is corrected. */
    val deadbandMs: Double = 4.0,
    /** Once correcting, keep going until the error is this small (hysteresis). */
    val correctionDoneMs: Double = 1.5,
    /** Above this error a seek is cheaper than a long speed nudge. */
    val hardResyncMs: Double = 120.0,
    /** Largest playback-speed change used for soft correction (0.02 = ±2%). */
    val maxSpeedNudge: Float = 0.02f,
    /** Soft correction aims to remove the error over roughly this long. */
    val correctionHorizonMs: Double = 1_500.0,
    /** Speed changes smaller than this are not worth an AudioTrack update. */
    val minSpeedStep: Float = 0.001f,
    /** How far ahead a phone schedules a start when it (re)joins a running track. */
    val joinLeadMs: Long = 400,
    /** Minimum time needed to seek and re-buffer a paused player before a scheduled start. */
    val minPrepareMs: Long = 150,
    /** Paused players further than this from the target are re-seeked (silent while paused). */
    val positionToleranceMs: Long = 1,
    /** Clock-offset changes smaller than this are slewed in gradually during playback. */
    val offsetSlewThresholdMs: Long = 50,
    /** Max offset slew per tick while playing (0.5 ms per 100 ms tick = 5 ms/s). */
    val offsetSlewPerTickNs: Long = 500_000,
    /** Learned latencies are clamped to this range. */
    val maxLearnedLatencyMs: Double = 500.0,
    val latencyLearningRate: Double = 0.7,
    /** One learning step never moves a latency estimate by more than this. */
    val maxLearningStepMs: Double = 60.0,
)
