package net.clickarr.provider.plex

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import net.clickarr.core.model.Artwork
import net.clickarr.core.model.ArtworkRef
import net.clickarr.core.model.Collection
import net.clickarr.core.model.Episode
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.MediaVersion
import net.clickarr.core.model.Movie
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.Show

/** Plex wire types to normalized model. Pure functions; unit tested with fixtures. */
internal class PlexMapper(private val provider: ProviderId) {
    fun ref(ratingKey: String) = MediaRef(provider, NativeItemId(ratingKey))

    fun library(d: PlexDirectory) = Library(
        ref = ref(d.key),
        name = d.title,
        kind = when (d.type) {
            "movie" -> LibraryKind.MOVIES
            "show" -> LibraryKind.SHOWS
            else -> LibraryKind.OTHER
        },
    )

    fun artwork(m: PlexMetadata) = Artwork(
        poster = m.thumb?.let { ArtworkRef(provider, it) },
        thumb = m.thumb?.let { ArtworkRef(provider, it) },
        backdrop = m.art?.let { ArtworkRef(provider, it) },
    )

    fun show(m: PlexMetadata) = Show(
        ref = ref(m.ratingKey),
        title = m.title,
        artwork = artwork(m),
        genres = m.genres.map { it.tag },
        year = m.year,
        studio = m.studio,
        network = m.studio,
        seasonCount = m.childCount ?: 0,
        episodeCount = m.leafCount ?: 0,
    )

    fun movie(m: PlexMetadata) = Movie(
        ref = ref(m.ratingKey),
        title = m.title,
        artwork = artwork(m),
        genres = m.genres.map { it.tag },
        year = m.year,
        studio = m.studio,
        runtime = runtime(m),
        media = versions(m),
    )

    fun episode(m: PlexMetadata) = Episode(
        ref = ref(m.ratingKey),
        title = m.title,
        artwork = artwork(m),
        genres = m.genres.map { it.tag },
        year = m.year,
        show = ref(m.grandparentRatingKey ?: ""),
        showTitle = m.grandparentTitle ?: "",
        season = m.parentRatingKey?.let { ref(it) },
        seasonIndex = m.parentIndex ?: 0,
        episodeIndex = m.index ?: 0,
        runtime = runtime(m),
        media = versions(m),
    )

    fun collection(m: PlexMetadata) = Collection(ref(m.ratingKey), m.title)

    fun playlist(m: PlexMetadata) = Playlist(ref(m.ratingKey), m.title)

    /** Episodes and movies become Playable; shows stay Show. Anything else is dropped. */
    fun item(m: PlexMetadata): MediaItem? = when (m.type) {
        "movie" -> movie(m)
        "episode" -> episode(m)
        "show" -> show(m)
        else -> null
    }

    /** File duration from the first media version, falling back to metadata duration. Never zero. */
    fun runtime(m: PlexMetadata): Duration {
        val ms = m.media.firstOrNull()?.duration ?: m.duration ?: 0L
        return ms.coerceAtLeast(0L).milliseconds
    }

    private fun versions(m: PlexMetadata): List<MediaVersion> = m.media.map { v ->
        MediaVersion(
            id = v.id?.toString() ?: "",
            container = v.container,
            videoCodec = v.videoCodec,
            audioCodec = v.audioCodec,
            width = v.width,
            height = v.height,
            bitrateKbps = v.bitrate,
            duration = (v.duration ?: m.duration ?: 0L).milliseconds,
        )
    }
}
