package com.songsync.app.data

import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.data.source.MusicSource

/** Search with a small in-memory cache, so flipping between sources or retyping is instant. */
class SearchRepository(private val sources: Map<TrackSource, MusicSource>) {

    private val cache = object : LinkedHashMap<Pair<TrackSource, String>, List<Track>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TrackSource, String>, List<Track>>) =
            size > MAX_CACHED_QUERIES
    }

    /** Call from one thread (the UI's); the network work itself runs off it. */
    suspend fun search(source: TrackSource, query: String): List<Track> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = source to trimmed.lowercase()
        cache[key]?.let { return it }
        return sources.getValue(source).search(trimmed).also { cache[key] = it }
    }

    private companion object {
        const val MAX_CACHED_QUERIES = 30
    }
}
