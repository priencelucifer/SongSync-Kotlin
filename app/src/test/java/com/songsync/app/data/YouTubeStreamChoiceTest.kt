package com.songsync.app.data

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.source.SourceException
import com.songsync.app.data.source.StreamChoice
import com.songsync.app.data.source.chooseStream
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeStreamChoiceTest {

    private val streams = listOf(
        StreamChoice(itag = 139, format = "M4A", averageBitrate = 48_000, original = true),
        StreamChoice(itag = 140, format = "M4A", averageBitrate = 129_000, original = true),
        StreamChoice(itag = 251, format = "WEBMA_OPUS", averageBitrate = 135_000, original = true),
        StreamChoice(itag = 251, format = "WEBMA_OPUS", averageBitrate = 160_000, original = false), // dubbed track
    )

    @Test
    fun `without a pin the original track with the highest bitrate wins`() {
        assertThat(streams[chooseStream(streams, pinnedItag = null)].itag).isEqualTo(251)
        assertThat(chooseStream(streams, pinnedItag = null)).isEqualTo(2) // not the dubbed one
    }

    @Test
    fun `a pinned itag is chosen even when another stream is better`() {
        assertThat(streams[chooseStream(streams, pinnedItag = 140)].itag).isEqualTo(140)
        assertThat(chooseStream(streams, pinnedItag = 251)).isEqualTo(2)
    }

    @Test
    fun `a missing pinned itag falls back only within the same format`() {
        val noOpus251 = streams.filter { it.itag != 251 } + StreamChoice(250, "WEBMA_OPUS", 70_000, true)
        assertThat(noOpus251[chooseStream(noOpus251, pinnedItag = 251)].itag).isEqualTo(250)

        val aacOnly = streams.filter { it.format == "M4A" }
        assertThrows(SourceException::class.java) { chooseStream(aacOnly, pinnedItag = 251) }
    }

    @Test
    fun `no streams is an error`() {
        assertThrows(SourceException::class.java) { chooseStream(emptyList(), pinnedItag = null) }
    }
}
