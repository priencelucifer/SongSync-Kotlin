package com.songsync.app.data.source

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes ExoPlayer's requests to YouTube's `/videoplayback` look like the clients the stream
 * URLs were issued for; googlevideo throttles or rejects requests that don't. Mirrors NewPipe's
 * YoutubeHttpDataSource (GPL-3.0-or-later) for progressive streams: a request number (`rn`),
 * POST with a fixed body, and client-matching headers. Range requests keep the Range header.
 */
class YouTubeStreamInterceptor : Interceptor {
    private val requestNumber = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.host.endsWith("googlevideo.com") || !url.encodedPath.startsWith("/videoplayback")) {
            return chain.proceed(request)
        }
        val urlString = url.toString()
        val newUrl = if (url.queryParameter("rn") != null) url
        else url.newBuilder().addQueryParameter("rn", requestNumber.getAndIncrement().toString()).build()

        val builder = request.newBuilder()
            .url(newUrl)
            .post(POST_BODY.toRequestBody())
            .header("TE", "trailers")
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(urlString)) YoutubeParsingHelper.getVisionOsUserAgent(null)
                else NewPipeDownloader.USER_AGENT,
            )
        if (YoutubeParsingHelper.isWebStreamingUrl(urlString)) {
            builder.header("Origin", YOUTUBE)
                .header("Referer", YOUTUBE)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        const val YOUTUBE = "https://www.youtube.com"
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
