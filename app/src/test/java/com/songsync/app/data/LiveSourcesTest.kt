package com.songsync.app.data

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.source.JioSaavnSource
import com.songsync.app.data.source.NewPipeDownloader
import com.songsync.app.data.source.YouTubeSource
import com.songsync.app.data.source.YouTubeStreamInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/**
 * Talks to the real services, so it is opt-in: ./gradlew testDebugUnitTest -PliveTests
 * Run it when a source starts failing in the app, to tell API changes from app bugs.
 */
class LiveSourcesTest {

    private val http = OkHttpClient()

    @Before
    fun onlyWhenRequested() {
        assumeTrue("live tests disabled", System.getProperty("songsync.liveTests") == "true")
    }

    private fun streamResponds(url: String, client: OkHttpClient = http): Int =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { it.code }

    @Test
    fun `JioSaavn search, decrypt and stream`() = runBlocking {
        val source = JioSaavnSource(http)
        val tracks = source.search("believer imagine dragons")
        assertThat(tracks).isNotEmpty()
        val resolved = source.resolve(tracks.first(), allowAlternatives = true)
        println("JioSaavn: ${resolved.track.title} @ ${resolved.track.bitrateKbps} kbps -> ${resolved.streamUrl}")
        assertThat(resolved.track.bitrateKbps).isNotNull()
        assertThat(streamResponds(resolved.streamUrl)).isAnyOf(200, 206)
    }

    @Test
    fun `YouTube search, extract and stream`() = runBlocking {
        NewPipe.init(NewPipeDownloader(http))
        val source = YouTubeSource()
        val tracks = source.search("believer imagine dragons")
        assertThat(tracks).isNotEmpty()
        val resolved = source.resolve(tracks.first(), allowAlternatives = true)
        println("YouTube: ${resolved.track.title} (${resolved.track.id}) -> ${resolved.streamUrl.take(80)}...")
        val playerClient = http.newBuilder().addInterceptor(YouTubeStreamInterceptor()).build()
        assertThat(streamResponds(resolved.streamUrl, playerClient)).isAnyOf(200, 206)
    }
}
