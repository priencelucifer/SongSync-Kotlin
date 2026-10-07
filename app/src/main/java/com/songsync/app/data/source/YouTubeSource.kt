package com.songsync.app.data.source

import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube via NewPipeExtractor (requires `NewPipe.init` at startup). Search prefers YouTube
 * Music songs, which have clean metadata, and falls back to regular videos.
 */
class YouTubeSource : MusicSource {

    override val source = TrackSource.YOUTUBE
    private val service get() = ServiceList.YouTube

    override suspend fun search(query: String): List<Track> = runInterruptible(Dispatchers.IO) {
        val songs = runCatching { searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }.getOrNull().orEmpty()
        val items = songs.ifEmpty { searchItems("$query audio", YoutubeSearchQueryHandlerFactory.VIDEOS) }
        items.mapNotNull(::toTrack).take(MAX_RESULTS)
    }

    override suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack = runInterruptible(Dispatchers.IO) {
        try {
            val stream = audioStream(track.id, track.itag)
            ResolvedTrack(track.copy(itag = stream.itag), stream.content)
        } catch (e: Exception) {
            if (!allowAlternatives || e is InterruptedException) throw e
            // As in the original app: if this upload is blocked (age gate, DRM, region), try an
            // equivalent one. The host then shares the replacement ID so every phone plays it.
            val alternatives = runCatching {
                searchItems("${track.title} ${track.artist} lyric", YoutubeSearchQueryHandlerFactory.VIDEOS)
            }.getOrDefault(emptyList()).mapNotNull(::toTrack).filter { it.id != track.id }.take(MAX_ALTERNATIVES)
            for (alternative in alternatives) {
                val stream = runCatching { audioStream(alternative.id, pinnedItag = null) }.getOrNull() ?: continue
                return@runInterruptible ResolvedTrack(
                    track.copy(id = alternative.id, durationMs = alternative.durationMs, itag = stream.itag),
                    stream.content,
                )
            }
            throw SourceException("YouTube blocked this song", e)
        }
    }

    private fun searchItems(query: String, filter: String): List<StreamInfoItem> {
        val extractor = service.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
    }

    private fun toTrack(item: StreamInfoItem): Track? {
        val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return null
        return Track(
            source = TrackSource.YOUTUBE,
            id = id,
            title = item.name.orEmpty(),
            artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
            artworkUrl = bestThumbnail(item.thumbnails),
            durationMs = item.duration.coerceAtLeast(0) * 1_000,
        )
    }

    /** The audio stream to play: the host's [pinnedItag] if given, otherwise the best one. */
    private fun audioStream(videoId: String, pinnedItag: Int?): AudioStream {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val choices = streams.map {
            StreamChoice(
                itag = it.itag,
                format = it.format?.name.orEmpty(),
                averageBitrate = it.averageBitrate,
                original = it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL,
            )
        }
        return streams[chooseStream(choices, pinnedItag)]
    }

    private fun bestThumbnail(images: List<Image>): String =
        images.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }?.url.orEmpty()

    private companion object {
        const val MAX_RESULTS = 20
        const val MAX_ALTERNATIVES = 5
    }
}

/** What matters about one YouTube audio stream when choosing it (testable without NewPipe). */
internal data class StreamChoice(val itag: Int, val format: String, val averageBitrate: Int, val original: Boolean)

/**
 * Index of the stream to play. Without a pin: the original-language track with the highest
 * bitrate. With the host's [pinnedItag]: exactly that stream, or failing that one in the same
 * format (same codec and encoder, so the same priming), never a different codec, because
 * different files of one video are offset by a constant that sync cannot see.
 */
internal fun chooseStream(streams: List<StreamChoice>, pinnedItag: Int?): Int {
    if (streams.isEmpty()) throw SourceException("No playable audio stream")
    val ranked = streams.indices.sortedWith(
        compareByDescending<Int> { streams[it].original }.thenByDescending { streams[it].averageBitrate },
    )
    if (pinnedItag == null || pinnedItag < 0) return ranked.first()
    ranked.firstOrNull { streams[it].itag == pinnedItag }?.let { return it }
    val pinnedFormat = YOUTUBE_ITAG_FORMATS[pinnedItag]
    ranked.firstOrNull { pinnedFormat != null && streams[it].format == pinnedFormat }?.let { return it }
    throw SourceException("This phone can't get the same YouTube audio as the host (format $pinnedItag)")
}

/** Container/codec of YouTube's common audio-only itags (NewPipe MediaFormat names). */
private val YOUTUBE_ITAG_FORMATS = mapOf(
    139 to "M4A", 140 to "M4A", 141 to "M4A",
    249 to "WEBMA_OPUS", 250 to "WEBMA_OPUS", 251 to "WEBMA_OPUS",
)
