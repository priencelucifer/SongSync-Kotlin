package com.songsync.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackSource { JIOSAAVN, YOUTUBE, CLICK_TEST }

/**
 * A playable song as shared between phones. Every phone resolves the actual stream URL
 * itself from these fields, so nothing here is tied to the host's IP address.
 */
@Serializable
data class Track(
    val source: TrackSource,
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String = "",
    val durationMs: Long = 0,
    /** JioSaavn: the DES-encrypted media URL returned by the search API. */
    val encryptedMediaUrl: String? = null,
    /** JioSaavn: whether a 320 kbps rendition is advertised. */
    val highQualityAvailable: Boolean = false,
    /** JioSaavn: bitrate the host verified, so every phone fetches the identical file. */
    val bitrateKbps: Int? = null,
    /**
     * YouTube: stream format (itag) the host chose, so every phone decodes the identical file.
     * Different itags of one video are not time-aligned (encoder priming differs).
     */
    val itag: Int? = null,
) {
    /** Stable identity used by the protocol; independent of the chosen bitrate. */
    val key: String get() = "${source.name}:$id"
}

/** A track plus the concrete URL this phone should stream it from. */
data class ResolvedTrack(
    val track: Track,
    val streamUrl: String,
)
