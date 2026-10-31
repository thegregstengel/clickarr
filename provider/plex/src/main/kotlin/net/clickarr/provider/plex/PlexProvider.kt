package net.clickarr.provider.plex

import java.util.UUID
import kotlin.time.Duration
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.model.ArtworkRef
import net.clickarr.core.model.Collection
import net.clickarr.core.model.Episode
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
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
import kotlin.time.Duration.Companion.milliseconds
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.AudioOption
import net.clickarr.provider.api.CaptionRequest
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import net.clickarr.provider.api.PlaybackChoice
import net.clickarr.provider.api.PlaybackOptions
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.api.PlaybackState
import net.clickarr.provider.api.Subtitle
import net.clickarr.provider.api.VideoOption
import net.clickarr.provider.api.allPages
import net.clickarr.provider.api.collectAll
import net.clickarr.provider.api.matches
import net.clickarr.provider.api.mimeFor
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
        http.pageOf(url("/library/sections/${library.id.value}/all", "type" to "2"), page, map::show)

    override suspend fun movies(library: MediaRef, page: Page): Outcome<PageOf<Movie>> =
        http.pageOf(url("/library/sections/${library.id.value}/all", "type" to "1"), page, map::movie)

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

    override suspend fun playbackSource(
        item: MediaRef,
        profile: DeviceProfile,
        startAt: Duration,
        captions: CaptionRequest? = null,
        choice: PlaybackChoice? = null,
    ): Outcome<PlaybackSource> =
        http.get(url("/library/metadata/${item.id.value}"), PlexResponse.serializer()).flatMap { r ->
            val m = r.container.metadata.firstOrNull()
                ?: return@flatMap Outcome.Failure(ClickarrError.NotFound("No item ${item.id.value}"))
            val mediaIndex = plexMediaIndex(m.media, choice?.mediaId)
            val media = m.media.getOrNull(mediaIndex)
            val part = media?.parts?.firstOrNull()
            val subtitles = PlexSubtitles.of(part)
            val audioId = plexAudioStreamId(part, choice)
            val tracks = subtitles.joinToString { "${it.codec}/${it.languageCode}${if (it.key != null) "/file" else ""}" }
            if (media != null && part?.key != null && profile.canDirectPlay(media)) {
                Log.i(TAG) {
                    "direct play ${m.title}: ${media.container}/${media.videoCodec}/${media.audioCodec}, " +
                        "mediaIndex=$mediaIndex audio=$audioId, subtitles [$tracks]"
                }
                Outcome.Success(
                    PlaybackSource.DirectPlay(
                        url = url(part.key, "X-Plex-Token" to token).toString(),
                        headers = emptyMap(),
                        startAt = startAt,
                        mimeType = mimeFor(media.container),
                        subtitles = plexSidecars(subtitles) { key -> url(key, "X-Plex-Token" to token).toString() },
                    ),
                )
            } else {
                // A transcode starts at the offset on the server, so a separate subtitle file would be out of step;
                // the chosen track is burned into the picture instead, and "none" keeps a Plex default out of it.
                val burn = captions?.let { PlexSubtitles.choose(subtitles, it.language) }
                Log.i(TAG) {
                    "transcode ${m.title}: ${media?.container}/${media?.videoCodec}/${media?.audioCodec}, " +
                        "mediaIndex=$mediaIndex audio=$audioId, subtitles [$tracks], " +
                        "captions ${if (captions == null) "off" else "on, burning ${burn?.let { "${it.id} ${it.codec}" } ?: "nothing"}"}"
                }
                if (burn != null && part?.id != null) {
                    http.touch(url("/library/parts/${part.id}", "subtitleStreamID" to burn.id.toString(), "allParts" to "1"), "PUT")
                }
                val session = UUID.randomUUID().toString()
                val hls = url(
                    "/video/:/transcode/universal/start.m3u8",
                    "path" to "/library/metadata/${m.ratingKey}",
                    "mediaIndex" to mediaIndex.toString(),
                    "partIndex" to "0",
                    "protocol" to "hls",
                    "directPlay" to "0",
                    "directStream" to "1",
                    "fastSeek" to "1",
                    "offset" to startAt.inWholeSeconds.toString(),
                    "session" to session,
                    "videoResolution" to "${profile.maxWidth}x${profile.maxHeight}",
                    "maxVideoBitrate" to profile.maxBitrateKbps?.toString(),
                    "audioStreamID" to audioId?.toString(),
                    "subtitles" to if (burn != null) "burn" else "none",
                    "subtitleStreamID" to burn?.id?.toString(),
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

    override suspend fun playbackOptions(item: MediaRef): Outcome<PlaybackOptions> =
        http.get(url("/library/metadata/${item.id.value}"), PlexResponse.serializer()).map { r ->
            val m = r.container.metadata.firstOrNull() ?: return@map PlaybackOptions()
            val videos = m.media.mapIndexed { i, media ->
                VideoOption(
                    id = media.id?.toString() ?: i.toString(),
                    label = plexVideoLabel(media),
                    duration = media.duration?.milliseconds,
                )
            }
            val part = m.media.firstOrNull()?.parts?.firstOrNull()
            val audios = part?.streams.orEmpty().filter { it.streamType == AUDIO_STREAM }.map { s ->
                AudioOption(
                    id = s.id.toString(),
                    label = s.displayTitle ?: listOfNotNull(s.languageCode, s.codec).joinToString(" · ").ifBlank { "Audio ${s.id}" },
                    language = s.languageCode,
                )
            }
            PlaybackOptions(videos, audios)
        }

    override suspend fun endPlayback(source: PlaybackSource): Outcome<Unit> = when (source) {
        is PlaybackSource.Hls -> http.touch(url("/video/:/transcode/universal/stop", "session" to source.sessionId))
        is PlaybackSource.DirectPlay -> Outcome.Success(Unit)
    }

    override fun themeUrl(ref: ArtworkRef): String = url(ref.path, "X-Plex-Token" to token).toString()

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

    override suspend fun markPlayed(item: MediaRef): Outcome<Unit> = http.touch(
        url("/:/scrobble", "key" to item.id.value, "identifier" to "com.plexapp.plugins.library"),
    )

}

internal fun DeviceProfile.canDirectPlay(media: PlexMedia): Boolean {
    val container = media.container?.lowercase().orEmpty()
    val video = media.videoCodec?.lowercase().orEmpty()
    val audio = media.audioCodec?.lowercase().orEmpty()
    val videoOk = video in videoCodecs || (video == "hevc" && supportsHevc)
    val sizeOk = (media.height ?: 0) <= maxHeight && (media.width ?: 0) <= maxWidth
    val bitrateOk = (media.bitrate ?: 0) <= (maxBitrateKbps ?: Int.MAX_VALUE)
    return container in containers && videoOk && audio in audioCodecs && sizeOk && bitrateOk
}

/** Index of the chosen Media, or 0 for Auto. */
internal fun plexMediaIndex(media: List<PlexMedia>, mediaId: String?): Int {
    if (mediaId == null) return 0
    val byId = media.indexOfFirst { it.id?.toString() == mediaId }
    if (byId >= 0) return byId
    return mediaId.toIntOrNull()?.takeIf { it in media.indices } ?: 0
}

/** Explicit stream id, else preferred language, else null (server default / player pick). */
internal fun plexAudioStreamId(part: PlexPart?, choice: PlaybackChoice?): Long? {
    choice ?: return null
    choice.audioId?.toLongOrNull()?.let { return it }
    val lang = choice.preferredAudioLanguage?.lowercase() ?: return null
    return part?.streams.orEmpty()
        .filter { it.streamType == AUDIO_STREAM }
        .firstOrNull { it.languageCode?.lowercase() == lang }
        ?.id
}

internal fun plexVideoLabel(media: PlexMedia): String {
    val res = media.height?.let { "${it}p" }
    val codec = media.videoCodec
    val rate = media.bitrate?.let { kbps ->
        if (kbps >= 1_000) "${kbps / 1_000}.${(kbps % 1_000) / 100} Mbps" else "$kbps kbps"
    }
    return listOfNotNull(res, codec, rate, media.container).joinToString(" · ").ifBlank { "Version" }
}

/** Separate subtitle files the player can parse, each fetched through Plex with the token. */
internal fun plexSidecars(streams: List<PlexStream>, urlFor: (String) -> String): List<Subtitle> =
    streams.mapNotNull { s ->
        val key = s.key ?: return@mapNotNull null
        val mime = PlexSubtitles.mimeType(s.codec) ?: return@mapNotNull null
        Subtitle(urlFor(key), mime, s.languageCode, s.displayTitle)
    }

private const val TAG = "Plex"
private const val AUDIO_STREAM = 2
