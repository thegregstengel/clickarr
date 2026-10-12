package net.clickarr.provider.jellyfin

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
import net.clickarr.core.model.Season
import net.clickarr.core.model.Show

/** Pure mapping from Jellyfin's JSON to Clickarr's model. Artwork paths carry the image tag so caches stay correct. */
internal class JellyfinMapper(private val provider: ProviderId) {
    fun ref(id: String) = MediaRef(provider, NativeItemId(id))

    fun library(v: JfItem) = Library(
        ref(v.id),
        v.name,
        when (v.collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.SHOWS
            else -> LibraryKind.OTHER
        },
    )

    fun show(v: JfItem) = Show(
        ref = ref(v.id),
        title = v.name,
        artwork = Artwork(
            poster = image(v.id, "Primary", v.imageTags["Primary"]),
            thumb = image(v.id, "Thumb", v.imageTags["Thumb"]) ?: backdrop(v.id, v.backdropTags),
            backdrop = backdrop(v.id, v.backdropTags),
        ),
        genres = v.genres,
        year = v.year,
        studio = v.studios.firstOrNull()?.name,
        network = v.studios.firstOrNull()?.name,
        seasonCount = v.childCount ?: 0,
        episodeCount = v.recursiveCount ?: 0,
        summary = v.overview,
    )

    fun season(v: JfItem) = Season(
        ref = ref(v.id),
        title = v.name,
        artwork = Artwork(poster = image(v.id, "Primary", v.imageTags["Primary"])),
        genres = v.genres,
        year = v.year,
        show = ref(v.seriesId.orEmpty()),
        index = v.index ?: 0,
    )

    fun episode(v: JfItem) = Episode(
        ref = ref(v.id),
        title = v.name,
        artwork = Artwork(
            poster = v.seriesId?.let { image(it, "Primary", v.seriesPrimaryTag) },
            thumb = image(v.id, "Primary", v.imageTags["Primary"]),
            backdrop = v.parentBackdropItemId?.let { backdrop(it, v.parentBackdropTags) },
        ),
        genres = v.genres,
        year = v.year,
        show = ref(v.seriesId.orEmpty()),
        showTitle = v.seriesName.orEmpty(),
        season = v.seasonId?.let(::ref),
        seasonIndex = v.seasonIndex ?: 0,
        episodeIndex = v.index ?: 0,
        runtime = runtime(v),
        media = versions(v),
        summary = v.overview,
    )

    fun movie(v: JfItem) = Movie(
        ref = ref(v.id),
        title = v.name,
        artwork = Artwork(
            poster = image(v.id, "Primary", v.imageTags["Primary"]),
            thumb = image(v.id, "Thumb", v.imageTags["Thumb"]) ?: backdrop(v.id, v.backdropTags),
            backdrop = backdrop(v.id, v.backdropTags),
        ),
        genres = v.genres,
        year = v.year,
        studio = v.studios.firstOrNull()?.name,
        runtime = runtime(v),
        media = versions(v),
        summary = v.overview,
    )

    fun collection(v: JfItem) = Collection(ref(v.id), v.name, v.childCount)

    fun playlist(v: JfItem) = Playlist(ref(v.id), v.name, v.childCount)

    fun item(v: JfItem): MediaItem? = when (v.type) {
        "Movie" -> movie(v)
        "Episode" -> episode(v)
        "Series" -> show(v)
        "Season" -> season(v)
        else -> null
    }

    private fun versions(v: JfItem): List<MediaVersion> = v.mediaSources.map { s ->
        val video = s.streams.firstOrNull { it.type == "Video" }
        MediaVersion(
            id = s.id,
            container = s.container?.substringBefore(','),
            videoCodec = video?.codec,
            audioCodec = s.streams.firstOrNull { it.type == "Audio" }?.codec,
            width = video?.width,
            height = video?.height,
            bitrateKbps = s.bitrate?.let { (it / BITS_PER_KBIT).toInt() },
            duration = ticks(s.runTimeTicks ?: v.runTimeTicks),
        )
    }

    /** The file's own length when the server knows it; metadata runtimes are often rounded. */
    private fun runtime(v: JfItem): Duration = ticks(v.mediaSources.firstOrNull()?.runTimeTicks ?: v.runTimeTicks)

    private fun image(id: String, type: String, tag: String?): ArtworkRef? =
        tag?.let { ArtworkRef(provider, "/Items/$id/Images/$type?tag=$it") }

    private fun backdrop(id: String, tags: List<String>): ArtworkRef? =
        tags.firstOrNull()?.let { ArtworkRef(provider, "/Items/$id/Images/Backdrop/0?tag=$it") }

    companion object {
        private const val TICKS_PER_MS = 10_000L
        private const val BITS_PER_KBIT = 1_000L

        /** Jellyfin counts time in 100-nanosecond ticks. */
        fun ticks(value: Long?): Duration = ((value ?: 0L) / TICKS_PER_MS).milliseconds

        fun toTicks(d: Duration): Long = d.inWholeMilliseconds * TICKS_PER_MS
    }
}
