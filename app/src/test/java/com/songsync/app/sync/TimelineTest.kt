package com.songsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineTest {

    private val ms = NANOS_PER_MS

    @Test
    fun `playing timeline advances in real time after the anchor`() {
        val s = PlaybackState(1, "t", playing = true, anchorHostNs = 10_000 * ms, anchorPositionMs = 5_000)
        assertThat(s.positionAt(10_000 * ms)).isEqualTo(5_000)
        assertThat(s.positionAt(12_500 * ms)).isEqualTo(7_500)
    }

    @Test
    fun `before a play anchor the track is held at the anchor position`() {
        val s = PlaybackState(1, "t", playing = true, anchorHostNs = 10_000 * ms, anchorPositionMs = 5_000)
        assertThat(s.positionAt(9_000 * ms)).isEqualTo(5_000)
    }

    @Test
    fun `paused timeline never moves`() {
        val s = PlaybackState(1, "t", playing = false, anchorHostNs = 10_000 * ms, anchorPositionMs = 5_000)
        assertThat(s.positionAt(99_000 * ms)).isEqualTo(5_000)
    }

    @Test
    fun `re-anchored but identical motion is a continuation`() {
        val a = PlaybackState(1, "t", playing = true, anchorHostNs = 0, anchorPositionMs = 0)
        val b = PlaybackState(2, "t", playing = true, anchorHostNs = 4_000 * ms, anchorPositionMs = 4_000)
        assertThat(b.isContinuationOf(a, atHostNs = 5_000 * ms)).isTrue()
        val seek = b.copy(anchorPositionMs = 9_000)
        assertThat(seek.isContinuationOf(a, atHostNs = 5_000 * ms)).isFalse()
        val future = b.copy(anchorHostNs = 6_000 * ms, anchorPositionMs = 6_000)
        assertThat(future.isContinuationOf(a, atHostNs = 5_000 * ms)).isFalse()
    }
}
