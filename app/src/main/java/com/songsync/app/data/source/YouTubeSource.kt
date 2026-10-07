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
            ResolvedTrack(track, audioUrl(track.id))
        } catch (e: Exception) {
            if (!allowAlternatives || e is InterruptedException) throw e
            // As in the original app: if this upload is blocked (age gate, DRM, region), try an
            // equivalent one. The host then shares the replacement ID so every phone plays it.
            val alternatives = runCatching {
                searchItems("${track.title} ${track.artist} lyric", YoutubeSearchQueryHandlerFactory.VIDEOS)
            }.getOrDefault(emptyList()).mapNotNull(::toTrack).filter { it.id != track.id }.take(MAX_ALTERNATIVES)
            for (alternative in alternatives) {
                val url = runCatching { audioUrl(alternative.id) }.getOrNull() ?: continue
                return@runInterruptible ResolvedTrack(track.copy(id = alternative.id, durationMs = alternative.durationMs), url)
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

    private fun audioUrl(videoId: String): String {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val stream = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull() ?: throw SourceException("No playable audio stream")
        return stream.content
    }

    private fun bestThumbnail(images: List<Image>): String =
        images.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }?.url.orEmpty()

    private companion object {
        const val MAX_RESULTS = 20
        const val MAX_ALTERNATIVES = 5
    }
}
