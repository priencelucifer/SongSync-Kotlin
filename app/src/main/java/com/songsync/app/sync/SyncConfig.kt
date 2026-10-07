package com.songsync.app.sync

/** Tuning constants for the sync engine. Defaults are starting points to refine on real phones. */
data class SyncConfig(
    /** How often the follower measures and corrects. */
    val tickMs: Long = 100,
    /**
     * Measurements right after a start or seek are unreliable: until AudioTrack timestamps are
     * available, ExoPlayer estimates the position and can be off by 100+ ms on some phones.
     */
    val settleMs: Long = 1_000,
    /** Samples (one per tick) whose median is the start error. */
    val settleSamples: Int = 5,
    /** Median window for the drift controller (samples, at [tickMs] each). */
    val windowSamples: Int = 10,
    val minSamplesForDecision: Int = 5,
    /**
     * Below this error nothing is corrected. With fine, gentle speed steps a tighter band does
     * not oscillate, and leaves room for ~1-2 ms of clock-offset error before 5 ms is heard.
     */
    val deadbandMs: Double = 3.0,
    /** Once correcting, keep going until the error is this small (hysteresis). */
    val correctionDoneMs: Double = 1.5,
    /** Errors above this, if they persist, are fixed by re-syncing instead of speed nudges. */
    val hardResyncMs: Double = 150.0,
    /** ...but only when the error stayed that large for this long (one bad reading is not enough). */
    val hardResyncSustainMs: Long = 2_000,
    /** ...and never this soon after a start: early positions can be wrong. */
    val minLockBeforeResyncMs: Long = 6_000,
    /** Repeated re-syncs back off: 6 s, 12 s, 24 s ... up to this. */
    val resyncBackoffBaseMs: Long = 6_000,
    val resyncBackoffMaxMs: Long = 60_000,
    /** Locked and calm for this long resets the back-off. */
    val resyncCalmResetMs: Long = 30_000,
    /** Largest playback-speed change used for soft correction (0.02 = ±2%), for big errors only. */
    val maxSpeedNudge: Float = 0.02f,
    /**
     * For errors below [smallErrorMs] the nudge is capped lower: accurate systems never correct
     * faster than ~0.05-0.1% (Snapcast), and gentle nudges cannot overshoot or be heard.
     */
    val smallErrorMaxNudge: Float = 0.005f,
    val smallErrorMs: Double = 20.0,
    /** Speeds are quantised to this step so tiny measurement noise does not cause changes. */
    val speedStep: Float = 0.001f,
    /** A speed is kept at least this long before changing again (each change has a cost). */
    val minSpeedDwellMs: Long = 1_500,
    /** Soft correction aims to remove the error over roughly this long. */
    val correctionHorizonMs: Double = 1_500.0,
    /** How far ahead a phone schedules a start when it (re)joins a running track. */
    val joinLeadMs: Long = 400,
    /** Minimum time needed to seek and re-buffer a paused player before a scheduled start. */
    val minPrepareMs: Long = 150,
    /**
     * A (re)start only happens once this much audio past the target is downloaded. Seeking to
     * data that is not there yet would stall again and chase the moving timeline forever.
     */
    val startHeadroomMs: Long = 1_500,
    /** If the missing audio is further ahead than this, jump instead of waiting for the download. */
    val catchUpWindowMs: Long = 15_000,
    /** A jump lands this far past the timeline so the download can finish before it arrives. */
    val leapLeadMs: Long = 4_000,
    /** A phone ahead of the timeline by up to this much waits for it instead of seeking back. */
    val maxWaitAheadMs: Long = 10_000,
    /** Paused players further than this from the target are re-seeked (silent while paused). */
    val positionToleranceMs: Long = 1,
    /** While audio plays, clock-offset changes smaller than this are slewed in gradually. */
    val offsetSlewThresholdMs: Long = 150,
    /** Max offset slew per tick while playing (0.5 ms per 100 ms tick = 5 ms/s). */
    val offsetSlewPerTickNs: Long = 500_000,
    /** Learned latencies are clamped to this range. */
    val maxLearnedLatencyMs: Double = 350.0,
    val latencyLearningRate: Double = 0.7,
    /** One learning step never moves a latency estimate by more than this. */
    val maxLearningStepMs: Double = 30.0,
)
