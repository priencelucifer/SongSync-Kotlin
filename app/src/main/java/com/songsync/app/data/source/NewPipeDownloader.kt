package com.songsync.app.data.source

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * NewPipeExtractor's HTTP layer on top of the app's shared OkHttp client.
 *
 * The extractor API is blocking. A plain blocking OkHttp call ignores thread interrupts, so a
 * cancelled search would keep its worker busy until the request finished, delaying the next
 * search. Here the call runs asynchronously and an interrupt (from `runInterruptible`) cancels it.
 */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody() ?: if (method == "POST") ByteArray(0).toRequestBody() else null
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val call = client.newCall(builder.build())
        val result = CompletableFuture<Response>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    try {
                        result.complete(
                            Response(it.code, it.message, it.headers.toMultimap(), it.body.string(), it.request.url.toString()),
                        )
                    } catch (e: IOException) {
                        result.completeExceptionally(e)
                    }
                }
            }
        })
        val response = try {
            result.get()
        } catch (_: InterruptedException) {
            call.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        }
        if (response.responseCode() == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
        return response
    }

    companion object {
        /** Same desktop browser identity NewPipe uses. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
