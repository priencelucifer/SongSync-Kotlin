package com.songsync.app.data

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.source.NewPipeDownloader
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NewPipeDownloaderTest {

    private val server = MockWebServer()
    private val downloader = NewPipeDownloader(OkHttpClient())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `passes the response through`() {
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val response = downloader.get(server.url("/x").toString())
        assertThat(response.responseCode()).isEqualTo(200)
        assertThat(response.responseBody()).isEqualTo("hello")
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(NewPipeDownloader.USER_AGENT)
    }

    @Test
    fun `interrupting a slow request aborts it promptly`() {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(10, TimeUnit.SECONDS).build())
        var failure: Throwable? = null
        val worker = thread {
            try {
                downloader.get(server.url("/slow").toString())
            } catch (e: Throwable) {
                failure = e
            }
        }
        Thread.sleep(300)
        val interruptedAt = System.nanoTime()
        worker.interrupt()
        worker.join(5_000)
        val tookMs = (System.nanoTime() - interruptedAt) / 1_000_000
        assertThat(worker.isAlive).isFalse()
        assertThat(failure).isInstanceOf(InterruptedIOException::class.java)
        assertThat(tookMs).isLessThan(1_000)
    }
}
