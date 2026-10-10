package net.clickarr.provider.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
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
import net.clickarr.core.model.Playable
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.model.Show
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.api.PlaybackState

/**
 * An in-memory provider with a small, realistic library. Used by the contract suite, by scheduler
 * and UI tests, and as the "demo" server in debug builds so the app can be exercised without Plex.
 */
class FakeMediaProvider(
    override val id: ProviderId = ProviderId("fake"),
    val library: FakeLibrary = FakeLibrary.sitcomsAndMovies(id),
    var failWith: ClickarrError? = null,
) : MediaProvider {
    override val kind: ProviderKind = ProviderKind.PLEX
    override val server: ServerInfo = ServerInfo(id, kind, "fake-server", "Fake Server", "http://fake.local:32400", "0.0.0")

    val endedSessions = mutableListOf<String>()
    val progressReports = mutableListOf<Triple<MediaRef, Duration, PlaybackState>>()

    private fun <T> guard(block: () -> T): Outcome<T> = failWith?.let { Outcome.Failure(it) } ?: Outcome.Success(block())

    override suspend fun ping(): Outcome<ServerInfo> = guard { server }

    override suspend fun libraries(): Outcome<List<Library>> = guard { library.libraries }

    override suspend fun shows(library: MediaRef, page: Page): Outcome<PageOf<Show>> = guard {
        val all = this.library.shows.filter { this.library.libraryOf[it.ref] == library }
        PageOf(all.drop(page.offset).take(page.size), page, all.size)
    }

    override suspend fun movies(library: MediaRef, page: Page): Outcome<PageOf<Movie>> = guard {
        val all = this.library.movies.filter { this.library.libraryOf[it.ref] == library }
        PageOf(all.drop(page.offset).take(page.size), page, all.size)
    }

    override suspend fun episodes(show: MediaRef): Outcome<List<Episode>> = guard {
        library.episodes.filter { it.show == show }.sortedWith(compareBy({ it.seasonIndex }, { it.episodeIndex }))
    }

    override suspend fun collections(library: MediaRef): Outcome<List<Collection>> = guard { this.library.collections.keys.toList() }

    override suspend fun playlists(): Outcome<List<Playlist>> = guard { library.playlists.keys.toList() }

    override suspend fun items(refs: List<MediaRef>): Outcome<List<MediaItem>> = guard {
        val index = library.allItems.associateBy { it.ref }
        refs.mapNotNull { index[it] }
    }

    override suspend fun resolve(source: ProgrammingSource): Outcome<List<Playable>> = guard { resolveInternal(source) }

    private fun resolveInternal(source: ProgrammingSource): List<Playable> = when (source) {
        is ProgrammingSource.Shows -> source.shows.flatMap { show ->
            library.episodes.filter { it.show == show }.sortedWith(compareBy({ it.seasonIndex }, { it.episodeIndex }))
        }
        is ProgrammingSource.Library -> {
            val movies = library.movies.filter { library.libraryOf[it.ref] == source.library }
            val eps = library.shows.filter { library.libraryOf[it.ref] == source.library }
                .flatMap { s -> library.episodes.filter { it.show == s.ref } }
            val f = source.filter
            (movies + eps).filter { p ->
                val item = p as MediaItem
                val genreOk = f.genres.isEmpty() || item.genres.any { g -> f.genres.any { it.equals(g, ignoreCase = true) } }
                val decade = f.decadeStart
                val decadeOk = decade == null || (item.year ?: -1) in decade until decade + 10
                genreOk && decadeOk
            }
        }
        is ProgrammingSource.Collection -> library.collections.entries.firstOrNull { it.key.ref == source.ref }?.value.orEmpty()
        is ProgrammingSource.Playlist -> library.playlists.entries.firstOrNull { it.key.ref == source.ref }?.value.orEmpty()
        is ProgrammingSource.Explicit -> source.items.mapNotNull { ref -> library.playables.firstOrNull { it.ref == ref } }
        is ProgrammingSource.Union -> source.sources.flatMap { resolveInternal(it) }.distinctBy { it.ref }
    }

    override suspend fun playbackSource(item: MediaRef, profile: DeviceProfile, startAt: Duration): Outcome<PlaybackSource> {
        failWith?.let { return Outcome.Failure(it) }
        val playable = library.playables.firstOrNull { it.ref == item }
            ?: return Outcome.Failure(ClickarrError.NotFound("No item ${item.id.value}"))
        return Outcome.Success(
            PlaybackSource.DirectPlay(
                url = "${server.baseUrl}/fake/${playable.ref.id.value}.mp4",
                headers = emptyMap(),
                startAt = startAt,
                mimeType = "video/mp4",
            ),
        )
    }

    override suspend fun endPlayback(source: PlaybackSource): Outcome<Unit> {
        if (source is PlaybackSource.Hls) endedSessions += source.sessionId
        return Outcome.Success(Unit)
    }

    override fun artworkUrl(ref: ArtworkRef, size: ArtworkSize): String = "${server.baseUrl}${ref.path}?w=${size.width}&h=${size.height}"

    override suspend fun reportProgress(item: MediaRef, position: Duration, state: PlaybackState): Outcome<Unit> {
        progressReports += Triple(item, position, state)
        return Outcome.Success(Unit)
    }

    val playedItems = mutableListOf<MediaRef>()

    override suspend fun markPlayed(item: MediaRef): Outcome<Unit> {
        playedItems += item
        return Outcome.Success(Unit)
    }
}

/** A tiny library shaped like a real one: two shows with seasons, a few movies, a collection, a playlist. */
class FakeLibrary(
    val libraries: List<Library>,
    val shows: List<Show>,
    val episodes: List<Episode>,
    val movies: List<Movie>,
    val collections: Map<Collection, List<Playable>>,
    val playlists: Map<Playlist, List<Playable>>,
    val libraryOf: Map<MediaRef, MediaRef>,
) {
    val playables: List<Playable> get() = episodes + movies
    val allItems: List<MediaItem> get() = shows + episodes + movies

    companion object {
        fun sitcomsAndMovies(provider: ProviderId): FakeLibrary {
            fun ref(id: String) = MediaRef(provider, NativeItemId(id))
            fun version(id: String, d: Duration) = MediaVersion(id, "mkv", "h264", "aac", 1920, 1080, 8000, d)
            val tvLib = Library(ref("lib-tv"), "TV Shows", LibraryKind.SHOWS)
            val movieLib = Library(ref("lib-movies"), "Movies", LibraryKind.MOVIES)

            val office = Show(
                ref("show-office"), "The Office", Artwork(poster = ArtworkRef(provider, "/art/office")),
                listOf("Comedy"), 2005, "NBC", "NBC", 2, 4,
            )
            val parks = Show(ref("show-parks"), "Parks and Recreation", Artwork(), listOf("Comedy"), 2009, "NBC", "NBC", 1, 2)
            val episodes = buildList {
                var n = 0
                for ((show, seasons) in listOf(office to 2, parks to 1)) {
                    for (s in 1..seasons) for (e in 1..2) {
                        n++
                        val d = (20 + n).minutes
                        add(
                            Episode(
                                ref = ref("ep-$n"),
                                title = "${show.title} S${s}E$e",
                                artwork = Artwork(thumb = ArtworkRef(provider, "/art/ep-$n")),
                                genres = show.genres,
                                year = show.year,
                                show = show.ref,
                                showTitle = show.title,
                                season = ref("season-${show.ref.id.value}-$s"),
                                seasonIndex = s,
                                episodeIndex = e,
                                runtime = d,
                                media = listOf(version("v-ep-$n", d)),
                            ),
                        )
                    }
                }
            }
            fun movie(id: String, title: String, genres: List<String>, year: Int, studio: String, minutes: Int) = Movie(
                ref("mv-$id"), title, Artwork(), genres, year, studio, minutes.minutes, listOf(version("v-$id", minutes.minutes)),
            )
            val movies = listOf(
                movie("fellowship", "The Fellowship of the Ring", listOf("Fantasy", "Adventure"), 2001, "New Line", 178),
                movie("towers", "The Two Towers", listOf("Fantasy", "Adventure"), 2002, "New Line", 179),
                movie("bttf", "Back to the Future", listOf("Comedy", "Sci-Fi"), 1985, "Universal", 116),
                movie("goonies", "The Goonies", listOf("Adventure", "Comedy"), 1985, "Warner", 114),
            )
            val lotr = Collection(ref("col-lotr"), "The Lord of the Rings")
            val playlist = Playlist(ref("pl-80s"), "80s Night")
            val libraryOf = buildMap {
                listOf(office, parks).forEach { put(it.ref, tvLib.ref) }
                movies.forEach { put(it.ref, movieLib.ref) }
            }
            return FakeLibrary(
                libraries = listOf(tvLib, movieLib),
                shows = listOf(office, parks),
                episodes = episodes,
                movies = movies,
                collections = mapOf(lotr to movies.take(2)),
                playlists = mapOf(playlist to movies.drop(2)),
                libraryOf = libraryOf,
            )
        }
    }
}
