package com.songsync.app.data

import com.songsync.app.calibration.CalibrationSignal
import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.data.source.ClickTrack
import com.songsync.app.data.source.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Turns any [Track] into a stream URL using the matching source. */
class TrackResolver(
    private val sources: Map<TrackSource, MusicSource>,
    private val cacheDir: File,
) {
    suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack = when (track.source) {
        TrackSource.CLICK_TEST -> withContext(Dispatchers.IO) {
            val slots = CalibrationSignal.slotsOf(track)
            val file = if (slots != null) CalibrationSignal.file(cacheDir, slots) else ClickTrack.file(cacheDir)
            ResolvedTrack(track, file.toURI().toString())
        }
        else -> sources.getValue(track.source).resolve(track, allowAlternatives)
    }
}
