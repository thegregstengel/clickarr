package net.clickarr.provider.jellyfin.fixtures

/**
 * The fake server's library: synthetic, with the same titles as the Plex fixtures so tests read alike. Nothing here
 * was recorded from a real server.
 */
internal data class FxItem(
    val id: String,
    val name: String,
    val type: String,
    val parent: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val studio: String? = null,
    val minutes: Int = 0,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val container: String = "mp4",
    val video: String = "h264",
    val audio: String = "aac",
    /** A second audio track, as on discs that carry TrueHD with an AC3 fallback. */
    val altAudio: String? = null,
    val width: Int = 1920,
    val height: Int = 1080,
    val subtitles: List<FxSubtitle> = emptyList(),
)

internal data class FxSubtitle(val index: Int, val codec: String, val language: String, val external: Boolean)

internal object JellyfinCatalog {
    const val SERVER_ID = "f1x7ure0000000000000000000000001"
    const val MOVIES = "lib-movies"
    const val SHOWS = "lib-shows"
    const val OFFICE = "series-office"
    const val PARKS = "series-parks"
    const val LOTR = "boxset-lotr"
    const val PLAYLIST = "playlist-80s"

    val libraries = listOf(MOVIES to "movies", SHOWS to "tvshows")
    val libraryNames = mapOf(MOVIES to "Movies", SHOWS to "Shows")

    private val srt = FxSubtitle(index = 2, codec = "subrip", language = "eng", external = true)
    private val pgs = FxSubtitle(index = 2, codec = "PGSSUB", language = "eng", external = false)

    val items: List<FxItem> = listOf(
        FxItem("movie-fellowship", "The Fellowship of the Ring", "Movie", MOVIES, 2001, listOf("Adventure", "Fantasy"), "New Line", 178),
        FxItem("movie-towers", "The Two Towers", "Movie", MOVIES, 2002, listOf("Adventure", "Fantasy"), "New Line", 179),
        FxItem(
            "movie-bttf", "Back to the Future", "Movie", MOVIES, 1985, listOf("Science Fiction", "Comedy"), "Universal", 116,
            container = "mkv", video = "hevc", audio = "truehd", width = 3840, height = 2160, subtitles = listOf(pgs),
        ),
        FxItem("movie-goonies", "The Goonies", "Movie", MOVIES, 1985, listOf("Adventure"), "Warner Bros.", 114, subtitles = listOf(srt)),
        FxItem(OFFICE, "The Office (US)", "Series", SHOWS, 2005, listOf("Comedy"), "NBC"),
        FxItem(PARKS, "Parks and Recreation", "Series", SHOWS, 2009, listOf("Comedy"), "NBC"),
        episode("ep-office-101", "Pilot", OFFICE, 1, 1),
        episode("ep-office-102", "Diversity Day", OFFICE, 1, 2),
        episode("ep-office-201", "The Dundies", OFFICE, 2, 1),
        episode("ep-office-202", "Sexual Harassment", OFFICE, 2, 2),
        episode("ep-parks-101", "Pilot", PARKS, 1, 1).copy(container = "mkv", audio = "truehd", altAudio = "ac3"),
        episode("ep-parks-102", "Canvassing", PARKS, 1, 2),
        FxItem(LOTR, "The Lord of the Rings", "BoxSet", MOVIES),
        FxItem(PLAYLIST, "80s Night", "Playlist", "playlists"),
    )

    /** Children in order: a collection's films, a playlist's entries. */
    val children = mapOf(
        LOTR to listOf("movie-fellowship", "movie-towers"),
        PLAYLIST to listOf("movie-bttf", "movie-goonies"),
    )

    private fun episode(id: String, name: String, series: String, season: Int, episode: Int) =
        FxItem(id, name, "Episode", series, 2005, listOf("Comedy"), minutes = 22, seriesId = series, season = season, episode = episode)

    fun byId(id: String): FxItem? = items.firstOrNull { it.id == id }
}
