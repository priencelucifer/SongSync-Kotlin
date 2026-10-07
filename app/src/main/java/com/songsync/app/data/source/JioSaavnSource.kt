package com.songsync.app.data.source

import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * JioSaavn via its public web API, as in the original app: `search.getResults`, then the
 * DES-wrapped media URL is decrypted locally. Unlike the original, the 320 kbps rendition is
 * only used when it is advertised *and* actually exists, falling back to 160 and 96 kbps.
 */
class JioSaavnSource(
    private val http: OkHttpClient,
    /** Highest bitrate the user allows (data saver), in kbps. */
    private val maxBitrateKbps: () -> Int = { 320 },
) : MusicSource {

    override val source = TrackSource.JIOSAAVN

    override suspend fun search(query: String): List<Track> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.jiosaavn.com")
            .addPathSegment("api.php")
            .addQueryParameter("__call", "search.getResults")
            .addQueryParameter("q", query)
            .addQueryParameter("p", "1")
            .addQueryParameter("n", "$PAGE_SIZE")
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw SourceException("JioSaavn search failed (HTTP ${response.code})")
            response.body.string()
        }
        return withContext(Dispatchers.Default) { parseSearch(body) }
    }

    override suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack {
        val encrypted = track.encryptedMediaUrl ?: throw SourceException("This result is not a playable song")
        val baseUrl = try {
            JioSaavnCrypto.decryptMediaUrl(encrypted)
        } catch (e: IllegalArgumentException) {
            throw SourceException("Could not read the stream address", e)
        }

        // Clients play exactly the rendition the host verified.
        track.bitrateKbps?.let { return ResolvedTrack(track, JioSaavnCrypto.withBitrate(baseUrl, it)) }

        val limit = maxBitrateKbps()
        val candidates = BITRATES.filter { it <= limit && (it != 320 || track.highQualityAvailable) }.ifEmpty { listOf(96) }
        for (kbps in candidates) {
            val url = JioSaavnCrypto.withBitrate(baseUrl, kbps)
            if (exists(url)) return ResolvedTrack(track.copy(bitrateKbps = kbps), url)
        }
        throw SourceException("This song is not available for streaming")
    }

    private suspend fun exists(url: String): Boolean {
        val request = Request.Builder().url(url).header("Range", "bytes=0-1").build()
        return http.newCall(request).await().use { it.code == 200 || it.code == 206 }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private val BITRATES = listOf(320, 160, 96)
        private val json = Json { ignoreUnknownKeys = true }
        private val artworkSize = Regex("\\d+x\\d+(?=\\.\\w+$)")

        internal fun parseSearch(body: String): List<Track> {
            val results = json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                // Albums and playlists come back without a media URL; only songs are playable.
                val encrypted = o.string("encrypted_media_url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = o.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(
                    source = TrackSource.JIOSAAVN,
                    id = id,
                    title = HtmlEntities.decode(o.firstNonBlank("song", "title").orEmpty()),
                    artist = HtmlEntities.decode(o.firstNonBlank("primary_artists", "singers", "music").orEmpty()),
                    artworkUrl = o.string("image")?.replace(artworkSize, "500x500").orEmpty(),
                    durationMs = (o.string("duration")?.toLongOrNull() ?: 0) * 1_000,
                    encryptedMediaUrl = encrypted,
                    highQualityAvailable = o.string("320kbps") == "true",
                )
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        private fun JsonObject.firstNonBlank(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> string(key)?.takeIf { it.isNotBlank() } }
    }
}
