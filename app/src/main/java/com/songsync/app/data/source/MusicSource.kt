package com.songsync.app.data.source

import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import java.io.IOException

interface MusicSource {
    val source: TrackSource
    suspend fun search(query: String): List<Track>
    suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack
}

/** A source-level failure with a message suitable for showing to the user. */
class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause)
