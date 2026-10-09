package net.clickarr.provider.plex

import java.util.UUID
import kotlin.time.Duration
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.model.ArtworkRef
import net.clickarr.core.model.Collection
import net.clickarr.core.model.Episode
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.Playable
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.model.Show
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.api.PlaybackState
import okhttp3.OkHttpClient

/**
 * Plex Media Server as a [MediaProvider]. One instance per connected server.
 * Endpoints are listed in proposal section 6.2; fixtures in src/test/resources mirror each one.
 */
class PlexProvider(
    override val id: ProviderId,
    override val server: ServerInfo,
    private val token: String,
    private val identity: ClientIdentity,
    client: OkHttpClient,
) : MediaProvider {
    override val kind = ProviderKind.PLEX
    private val http = PlexHttp(client, identity) { token }
    private val map = PlexMapper(id)
    private val base get() = server.baseUrl

    private fun url(path: String, vararg query: Pair<String, String?>) = PlexHttp.url(base, path, *query)

    override suspend fun ping(): Outcome<ServerInfo> =
        http.get(url("/"), PlexResponse.serializer()).map { r ->
            server.copy(
                serverIdentity = r.container.machineIdentifier ?: server.serverIdentity,
                name = r.container.friendlyName ?: server.name,
                version = r.container.version,
            )
        }

    override suspend fun libraries(): Outcome<List<Library>> =
        http.get(url("/library/sections"), PlexResponse.serializer()).map { r -> r.container.directories.map(map::library) }

    override suspend fun shows(library: MediaRef, page: Page): Outcome<PageOf<Show>> =
        pageOf(url("/library/sections/${library.id.value}/all", "type" to "2"), page, map::show)

    override suspend fun movies(library: MediaRef, page: Page): Outcome<PageOf<Movie>> =
        pageOf(url("/library/sections/${library.id.value}/all", "type" to "1"), page, map::movie)

    override suspend fun episodes(show: MediaRef): Outcome<List<Episode>> =
        http.get(url("/library/metadata/${show.id.value}/allLeaves"), PlexResponse.serializer()).map { r ->
            r.container.metadata.filter { it.type == "episode" }.map(map::episode)
                .sortedWith(compareBy({ it.seasonIndex }, { it.episodeIndex }))
        }

    override suspend fun collections(library: MediaRef): Outcome<List<Collection>> =
        http.get(url("/library/sections/${library.id.value}/collections"), PlexResponse.serializer())
            .map { r -> r.container.metadata.map(map::collection) }

    override suspend fun playlists(): Outcome<List<Playlist>> =
        http.get(url("/playlists", "playlistType" to "video"), PlexResponse.serializer())
            .map { r -> r.container.metadata.map(map::playlist) }

    override suspend fun items(refs: List<MediaRef>): Outcome<List<MediaItem>> {
        if (refs.isEmpty()) return Outcome.Success(emptyList())
        val keys = refs.joinToString(",") { it.id.value }
        return http.get(url("/library/metadata/$keys"), PlexResponse.serializer()).map { r ->
            val byKey = r.container.metadata.mapNotNull { m -> map.item(m)?.let { it.ref to it } }.toMap()
            refs.mapNotNull { byKey[it] }
        }
    }

    override suspend fun resolve(source: ProgrammingSource): Outcome<List<Playable>> = when (source) {
        is ProgrammingSource.Shows -> collectAll(source.shows) { episodes(it) }
        is ProgrammingSource.Library -> resolveLibrary(source)
        is ProgrammingSource.Collection ->
            playablesFrom(url("/library/collections/${source.ref.id.value}/children"))
        is ProgrammingSource.Playlist -> playablesFrom(url("/playlists/${source.ref.id.value}/items"))
        is ProgrammingSource.Explicit -> items(source.items).map { list -> list.filterIsInstance<Playable>() }
        is ProgrammingSource.Union -> collectAll(source.sources) { resolve(it) }.map { it.distinctBy { p -> p.ref } }
    }

    private suspend fun resolveLibrary(source: ProgrammingSource.Library): Outcome<List<Playable>> {
        val kind = libraries().flatMap { libs ->
            libs.firstOrNull { it.ref == source.library }?.let { Outcome.Success(it.kind) }
                ?: Outcome.Failure(ClickarrError.NotFound("Library ${source.library.id.value} not found"))
        }
        return kind.flatMap { k ->
            when (k) {
                LibraryKind.MOVIES -> allPages { movies(source.library, it) }.map { all -> all.filter { source.filter.matches(it) } }
                LibraryKind.SHOWS -> allPages { shows(source.library, it) }.flatMap { all ->
                    collectAll(all.filter { source.filter.matches(it) }.map { it.ref }) { episodes(it) }
                }
                LibraryKind.OTHER -> Outcome.Success(emptyList())
            }
        }
    }

    /** A collection or playlist's children: shows expand to episodes, movies and episodes pass through. */
    private suspend fun playablesFrom(listUrl: okhttp3.HttpUrl): Outcome<List<Playable>> =
        http.get(listUrl, PlexResponse.serializer()).flatMap { r ->
            val direct = r.container.metadata.mapNotNull { map.item(it) as? Playable }
            val shows = r.container.metadata.filter { it.type == "show" }.map { map.ref(it.ratingKey) }
            collectAll(shows) { episodes(it) }.map { fromShows -> direct + fromShows }
        }

    override suspend fun playbackSource(item: MediaRef, profile: DeviceProfile, startAt: Duration): Outcome<PlaybackSource> =
        http.get(url("/library/metadata/${item.id.value}"), PlexResponse.serializer()).flatMap { r ->
            val m = r.container.metadata.firstOrNull() ?: return@flatMap Outcome.Failure(ClickarrError.NotFound("No item ${item.id.value}"))
            val media = m.media.firstOrNull()
            val part = media?.parts?.firstOrNull()
            if (media != null && part?.key != null && profile.canDirectPlay(media)) {
                Outcome.Success(
                    PlaybackSource.DirectPlay(
                        url = url(part.key, "X-Plex-Token" to token).toString(),
                        headers = emptyMap(),
                        startAt = startAt,
                        mimeType = mimeFor(media.container),
                    ),
                )
            } else {
                val session = UUID.randomUUID().toString()
                val hls = url(
                    "/video/:/transcode/universal/start.m3u8",
                    "path" to "/library/metadata/${m.ratingKey}",
                    "mediaIndex" to "0",
                    "partIndex" to "0",
                    "protocol" to "hls",
                    "directPlay" to "0",
                    "directStream" to "1",
                    "fastSeek" to "1",
                    "offset" to startAt.inWholeSeconds.toString(),
                    "session" to session,
                    "videoResolution" to "${profile.maxWidth}x${profile.maxHeight}",
                    "maxVideoBitrate" to profile.maxBitrateKbps?.toString(),
                    "X-Plex-Client-Identifier" to identity.deviceId.value,
                    "X-Plex-Platform" to identity.platform,
                    "X-Plex-Product" to "Clickarr",
                    "X-Plex-Token" to token,
                )
                Outcome.Success(
                    PlaybackSource.Hls(url = hls.toString(), headers = emptyMap(), startAt = Duration.ZERO, sessionId = session),
                )
            }
        }

    override suspend fun endPlayback(source: PlaybackSource): Outcome<Unit> = when (source) {
        is PlaybackSource.Hls -> http.touch(url("/video/:/transcode/universal/stop", "session" to source.sessionId))
        is PlaybackSource.DirectPlay -> Outcome.Success(Unit)
    }

    override fun artworkUrl(ref: ArtworkRef, size: ArtworkSize): String = url(
        "/photo/:/transcode",
        "width" to size.width.toString(),
        "height" to size.height.toString(),
        "minSize" to "1",
        "upscale" to "1",
        "url" to ref.path,
        "X-Plex-Token" to token,
    ).toString()

    override suspend fun reportProgress(item: MediaRef, position: Duration, state: PlaybackState): Outcome<Unit> = http.touch(
        url(
            "/:/timeline",
            "ratingKey" to item.id.value,
            "key" to "/library/metadata/${item.id.value}",
            "state" to state.name.lowercase(),
            "time" to position.inWholeMilliseconds.toString(),
            "identifier" to "com.plexapp.plugins.library",
        ),
    )

    // Helpers

    private suspend fun <T> pageOf(listUrl: okhttp3.HttpUrl, page: Page, mapper: (PlexMetadata) -> T): Outcome<PageOf<T>> =
        http.get(
            listUrl,
            PlexResponse.serializer(),
            mapOf("X-Plex-Container-Start" to page.offset.toString(), "X-Plex-Container-Size" to page.size.toString()),
        ).map { r ->
            val items = r.container.metadata.map(mapper)
            PageOf(items, page, r.container.totalSize ?: (page.offset + items.size))
        }

    private suspend fun <T> allPages(fetch: suspend (Page) -> Outcome<PageOf<T>>): Outcome<List<T>> {
        val out = ArrayList<T>()
        var page = Page.first()
        while (true) {
            val result = fetch(page)
            val p = when (result) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return result
            }
            out += p.items
            if (!p.hasMore || p.items.isEmpty()) return Outcome.Success(out)
            page = page.next()
        }
    }

    private suspend fun <I, T> collectAll(inputs: List<I>, fetch: suspend (I) -> Outcome<List<T>>): Outcome<List<T>> {
        val out = ArrayList<T>()
        for (i in inputs) {
            when (val r = fetch(i)) {
                is Outcome.Success -> out += r.value
                is Outcome.Failure -> return r
            }
        }
        return Outcome.Success(out)
    }

    private fun mimeFor(container: String?): String? = when (container?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> null
    }
}

/** Client-side evaluation of a MediaFilter against normalized items (proposal 6.2: no tag-id round trips in the MVP). */
internal fun MediaFilter.matches(item: MediaItem): Boolean {
    val year = item.year
    val studio = (item as? Movie)?.studio ?: (item as? Show)?.studio
    val network = (item as? Show)?.network
    return (genres.isEmpty() || item.genres.any { it in genres }) &&
        (decadeStart == null || (year != null && year in decadeStart until decadeStart + 10)) &&
        (yearFrom == null || (year != null && year >= yearFrom)) &&
        (yearTo == null || (year != null && year <= yearTo)) &&
        (studios.isEmpty() || studio in studios) &&
        (networks.isEmpty() || network in networks)
}

internal fun DeviceProfile.canDirectPlay(media: PlexMedia): Boolean {
    val container = media.container?.lowercase() ?: return false
    val video = media.videoCodec?.lowercase() ?: return false
    val audio = media.audioCodec?.lowercase() ?: return false
    val hevcOk = video != "hevc" || supportsHevc
    return container in containers && (video in videoCodecs || (video == "hevc" && supportsHevc)) && hevcOk &&
        audio in audioCodecs &&
        (media.height ?: 0) <= maxHeight && (media.width ?: 0) <= maxWidth &&
        (maxBitrateKbps == null || (media.bitrate ?: 0) <= maxBitrateKbps)
}
