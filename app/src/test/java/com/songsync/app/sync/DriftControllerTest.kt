package com.songsync.app.sync

import com.google.common.truth.Truth.assertThat
import com.songsync.app.sync.DriftController.Decision
import org.junit.Test

class DriftControllerTest {

    private val config = SyncConfig()

    private fun controllerWith(vararg errors: Double) = DriftController(config).apply { errors.forEach(::addSample) }
    private fun DriftController.decide() = decide(nowNs = 0)

    @Test
    fun `waits for enough samples`() {
        assertThat(controllerWith(50.0, 50.0).decide()).isEqualTo(Decision.Wait)
    }

    @Test
    fun `ignores errors inside the deadband`() {
        val d = controllerWith(*DoubleArray(10) { config.deadbandMs - 1 }).decide()
        assertThat(d).isEqualTo(Decision.Speed(1f))
    }

    @Test
    fun `slows down when ahead and speeds up when behind`() {
        val ahead = controllerWith(*DoubleArray(10) { 30.0 }).decide() as Decision.Speed
        val behind = controllerWith(*DoubleArray(10) { -30.0 }).decide() as Decision.Speed
        assertThat(ahead.speed).isLessThan(1f)
        assertThat(behind.speed).isGreaterThan(1f)
        assertThat(ahead.speed).isWithin(config.speedStep).of(1f - (30.0 / config.correctionHorizonMs).toFloat())
    }

    @Test
    fun `speed nudges are clamped and quantised`() {
        val d = controllerWith(*DoubleArray(10) { 110.0 }).decide() as Decision.Speed
        assertThat(d.speed).isEqualTo(1f - config.maxSpeedNudge)
        val tiny = controllerWith(*DoubleArray(10) { config.deadbandMs + 0.5 }).decide() as Decision.Speed
        assertThat(tiny.speed).isLessThan(1f) // never rounded to "no change"
        assertThat(tiny.speed).isAtLeast(1f - 4 * config.speedStep)
    }

    @Test
    fun `small errors get gentle nudges, large ones the full range`() {
        val small = controllerWith(*DoubleArray(10) { 7.5 }).decide() as Decision.Speed
        assertThat(small.speed).isWithin(1e-6f).of(1f - config.smallErrorMaxNudge) // 7.5/1500 = 0.5%, the cap
        val medium = controllerWith(*DoubleArray(10) { 15.0 }).decide() as Decision.Speed
        assertThat(medium.speed).isWithin(1e-6f).of(0.99f) // 15/1500 = 1%, not capped
        val large = controllerWith(*DoubleArray(10) { -60.0 }).decide() as Decision.Speed
        assertThat(large.speed).isWithin(1e-6f).of(1f + 0.04f.coerceAtMost(config.maxSpeedNudge))
    }

    @Test
    fun `only a sustained large error asks for a re-sync`() {
        val c = controllerWith(*DoubleArray(10) { 500.0 })
        val first = c.decide(nowNs = 0)
        assertThat(first).isEqualTo(Decision.Speed(1f - config.maxSpeedNudge)) // not yet: could be a bad reading
        val later = c.decide(nowNs = config.hardResyncSustainMs * NANOS_PER_MS)
        assertThat(later).isEqualTo(Decision.HardResync(Decision.Speed(1f - config.maxSpeedNudge)))
    }

    @Test
    fun `median shrugs off a single outlier`() {
        val d = controllerWith(0.0, 1.0, -1.0, 0.0, 400.0, 1.0, 0.0).decide()
        assertThat(d).isEqualTo(Decision.Speed(1f))
    }

    @Test
    fun `keeps correcting until well inside the deadband`() {
        val c = DriftController(config)
        repeat(10) { c.addSample(config.deadbandMs + 2) }
        assertThat((c.decide() as Decision.Speed).speed).isLessThan(1f)
        // Now between "done" and deadband: hysteresis keeps the correction going.
        repeat(10) { c.addSample((config.deadbandMs + config.correctionDoneMs) / 2) }
        assertThat((c.decide() as Decision.Speed).speed).isLessThan(1f)
        repeat(10) { c.addSample(config.correctionDoneMs / 2) }
        assertThat(c.decide()).isEqualTo(Decision.Speed(1f))
    }
}
