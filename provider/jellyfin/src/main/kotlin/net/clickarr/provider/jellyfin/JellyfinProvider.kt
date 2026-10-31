package net.clickarr.provider.jellyfin

import kotlin.time.Duration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import net.clickarr.provider.api.pageTotalWhenUnknown
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * A Jellyfin server as a [MediaProvider] (ADR 0022), one instance per connected server and signed-in user. Written
 * from Jellyfin's published REST API; listings are per user because Jellyfin's libraries and played state are.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class JellyfinProvider(
    override val id: ProviderId,
    override val server: ServerInfo,
    private val token: String,
    private val userId: String,
    private val identity: ClientIdentity,
    client: OkHttpClient,
) : MediaProvider {
    override val kind = ProviderKind.JELLYFIN
    private val http = JellyfinHttp(client, identity) { token }
    private val map = JellyfinMapper(id)
    private val base get() = server.baseUrl

    private fun url(path: String, vararg query: Pair<String, String?>) = JellyfinHttp.url(base, path, *query)

    /** A listing of the user's items; [query] narrows it. */
    private fun items(vararg query: Pair<String, String?>) = url("/Items", "userId" to userId, *query)

    override suspend fun ping(): Outcome<ServerInfo> =
        http.get(url("/System/Info"), JfSystemInfo.serializer()).map { r ->
            server.copy(serverIdentity = r.id, name = r.serverName ?: server.name, version = r.version)
        }

    override suspend fun libraries(): Outcome<List<Library>> =
        http.get(url("/UserViews", "userId" to userId), JfItems.serializer()).map { r -> r.items.map(map::library) }

    override suspend fun shows(library: MediaRef, page: Page): Outcome<PageOf<Show>> =
        pageOf(library, "Series", SHOW_FIELDS, page, map::show)

    override suspend fun movies(library: MediaRef, page: Page): Outcome<PageOf<Movie>> =
        pageOf(library, "Movie", PLAYABLE_FIELDS, page, map::movie)

    private suspend fun <T> pageOf(library: MediaRef, type: String, fields: String, page: Page, mapper: (JfItem) -> T): Outcome<PageOf<T>> =
        http.get(
            items(
                "parentId" to library.id.value,
                "includeItemTypes" to type,
                "recursive" to "true",
                "sortBy" to "SortName",
                "fields" to fields,
                "startIndex" to page.offset.toString(),
                "limit" to page.size.toString(),
            ),
            JfItems.serializer(),
        ).map { r ->
            val list = r.items.map(mapper)
            PageOf(list, page, r.total ?: pageTotalWhenUnknown(page, list.size))
        }

    override suspend fun episodes(show: MediaRef): Outcome<List<Episode>> =
        http.get(url("/Shows/${show.id.value}/Episodes", "userId" to userId, "fields" to PLAYABLE_FIELDS), JfItems.serializer())
            .map { r ->
                r.items.filter { it.type == "Episode" }.map(map::episode)
                    .sortedWith(compareBy({ it.seasonIndex }, { it.episodeIndex }))
            }

    /** Jellyfin keeps collections outside libraries; asking under a library returns the ones holding its items. */
    override suspend fun collections(library: MediaRef): Outcome<List<Collection>> =
        http.get(
            items("parentId" to library.id.value, "includeItemTypes" to "BoxSet", "recursive" to "true", "fields" to "ChildCount"),
            JfItems.serializer(),
        ).map { r -> r.items.map(map::collection) }

    override suspend fun playlists(): Outcome<List<Playlist>> =
        http.get(
            items("includeItemTypes" to "Playlist", "recursive" to "true", "mediaTypes" to "Video", "fields" to "ChildCount"),
            JfItems.serializer(),
        ).map { r -> r.items.map(map::playlist) }

    override suspend fun items(refs: List<MediaRef>): Outcome<List<MediaItem>> {
        if (refs.isEmpty()) return Outcome.Success(emptyList())
        val chunks = refs.map { it.id.value }.distinct().chunked(IDS_PER_REQUEST)
        return collectAll(chunks) { chunk ->
            http.get(items("ids" to chunk.joinToString(","), "fields" to PLAYABLE_FIELDS), JfItems.serializer())
                .map { r -> r.items.mapNotNull(map::item) }
        }.map { found ->
            val byRef = found.associateBy { it.ref }
            refs.mapNotNull { byRef[it] }
        }
    }

    override suspend fun resolve(source: ProgrammingSource): Outcome<List<Playable>> = when (source) {
        is ProgrammingSource.Shows -> collectAll(source.shows) { episodes(it) }
        is ProgrammingSource.Library -> resolveLibrary(source)
        is ProgrammingSource.Collection -> playablesFrom(items("parentId" to source.ref.id.value, "fields" to PLAYABLE_FIELDS))
        is ProgrammingSource.Playlist ->
            playablesFrom(url("/Playlists/${source.ref.id.value}/Items", "userId" to userId, "fields" to PLAYABLE_FIELDS))
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

    /** A collection or playlist's children, in order: shows expand to their episodes, movies and episodes pass through. */
    private suspend fun playablesFrom(listUrl: HttpUrl): Outcome<List<Playable>> =
        http.get(listUrl, JfItems.serializer()).flatMap { r ->
            val out = ArrayList<Playable>()
            for (v in r.items) {
                if (v.type == "Series") {
                    when (val eps = episodes(map.ref(v.id))) {
                        is Outcome.Success -> out += eps.value
                        is Outcome.Failure -> return@flatMap eps
                    }
                } else {
                    (map.item(v) as? Playable)?.let(out::add)
                }
            }
            Outcome.Success(out)
        }

    override suspend fun playbackSource(
        item: MediaRef,
        profile: DeviceProfile,
        startAt: Duration,
        captions: CaptionRequest? = null,
        choice: PlaybackChoice? = null,
    ): Outcome<PlaybackSource> {
        // One round trip: the decision lists the file's streams, so the caption track is chosen from its answer. The
        // request asks for no subtitles (-1) so a server default is never burned in; a burn is added to the HLS URL.
        val audioIndex = choice?.audioId?.toIntOrNull()
        val body = JellyfinProfile.playbackInfoBody(
            userId,
            profile,
            JellyfinMapper.toTicks(startAt),
            subtitleIndex = null,
            mediaSourceId = choice?.mediaId,
            audioStreamIndex = audioIndex,
        )
        return http.post(url("/Items/${item.id.value}/PlaybackInfo", "userId" to userId), body, JfPlaybackInfo.serializer())
            .flatMap { info ->
                val source = info.mediaSources.firstOrNull { choice?.mediaId == null || it.id == choice.mediaId }
                    ?: info.mediaSources.firstOrNull()
                    ?: return@flatMap Outcome.Failure(ClickarrError.NotFound("Jellyfin offered no way to play ${item.id.value}"))
                val chosen = JellyfinProfile.choose(source.streams, captions)
                decide(item.id.value, source, info.playSessionId, startAt, chosen, captions != null, profile, choice)
            }
    }

    override suspend fun playbackOptions(item: MediaRef): Outcome<PlaybackOptions> =
        http.get(
            url("/Items/${item.id.value}", "userId" to userId, "fields" to "MediaSources"),
            JfItem.serializer(),
        ).map { v ->
            val sources = v.mediaSources
            val videos = sources.map { s ->
                val video = s.streams.firstOrNull { it.type == "Video" }
                val res = video?.height?.let { "${it}p" }
                val rate = s.bitrate?.let { b ->
                    val kbps = (b / 1_000).toInt()
                    if (kbps >= 1_000) "${kbps / 1_000}.${(kbps % 1_000) / 100} Mbps" else "$kbps kbps"
                }
                VideoOption(
                    id = s.id,
                    label = listOfNotNull(res, video?.codec, rate, s.container).joinToString(" · ").ifBlank { s.id },
                    duration = s.runTimeTicks?.let { JellyfinMapper.ticks(it) },
                )
            }
            val audios = sources.firstOrNull()?.streams.orEmpty().filter { it.type == "Audio" }.map { s ->
                AudioOption(
                    id = s.index.toString(),
                    label = s.displayTitle ?: listOfNotNull(s.language, s.codec).joinToString(" · ").ifBlank { "Audio ${s.index}" },
                    language = s.language,
                )
            }
            PlaybackOptions(videos, audios)
        }

    private fun decide(
        itemId: String,
        source: JfMediaSource,
        session: String?,
        startAt: Duration,
        chosen: JfStream?,
        captionsOn: Boolean,
        profile: DeviceProfile,
        choice: PlaybackChoice?,
    ): Outcome<PlaybackSource> {
        val tracks = source.streams.filter { it.type == "Subtitle" }
            .joinToString { "${it.codec}/${it.language}${if (it.isExternal) "/file" else ""}" }
        val audioPick = choice?.audioId ?: choice?.preferredAudioLanguage?.let { lang ->
            source.streams.firstOrNull { it.type == "Audio" && it.language?.equals(lang, ignoreCase = true) == true }
                ?.index?.toString()
        }
        val transcode = source.transcodingUrl
        return when {
            source.supportsDirectPlay || JellyfinProfile.onlyAudioStandsInTheWay(source, profile) -> {
                Log.i(TAG) { "direct play $itemId: ${source.container}, audio=$audioPick, subtitles [$tracks]" }
                Outcome.Success(
                    PlaybackSource.DirectPlay(
                        url = url(
                            "/Videos/$itemId/stream",
                            "static" to "true",
                            "mediaSourceId" to source.id,
                            "playSessionId" to session,
                        ).toString(),
                        headers = mapOf("Authorization" to http.authorization()),
                        startAt = startAt,
                        mimeType = mimeFor(source.container?.substringBefore(',')),
                        subtitles = sidecars(source),
                    ),
                )
            }
            transcode != null -> {
                // The HLS playlist covers the whole programme and the player seeks to the offset, so the server starts
                // transcoding there. Captions in a transcode are burned in, as with Plex.
                val burn = chosen?.takeIf { captionsOn }
                Log.i(TAG) {
                    "transcode $itemId: ${source.container}, audio=$audioPick, subtitles [$tracks], " +
                        "captions ${if (!captionsOn) "off" else "on, burning ${burn?.let { "${it.index} ${it.codec}" } ?: "nothing"}"}"
                }
                val hls = (base.trimEnd('/') + transcode).toHttpUrl().newBuilder()
                    .removeAllQueryParameters("SubtitleStreamIndex").removeAllQueryParameters("SubtitleMethod")
                    .removeAllQueryParameters("AudioStreamIndex")
                    .apply {
                        if (burn != null) {
                            addQueryParameter("SubtitleStreamIndex", burn.index.toString())
                            addQueryParameter("SubtitleMethod", "Encode")
                        }
                        audioPick?.let { addQueryParameter("AudioStreamIndex", it) }
                    }.build()
                Outcome.Success(
                    PlaybackSource.Hls(
                        url = hls.toString(),
                        headers = mapOf("Authorization" to http.authorization()),
                        startAt = startAt,
                        sessionId = session ?: hls.queryParameter("PlaySessionId").orEmpty(),
                    ),
                )
            }
            else -> Outcome.Failure(ClickarrError.Unknown("Jellyfin can neither direct play nor transcode $itemId"))
        }
    }

    /** Text subtitles the server offers as separate files, fetched with the token since the player sends no headers. */
    private fun sidecars(source: JfMediaSource): List<Subtitle> = source.streams.mapNotNull { s ->
        val delivery = s.deliveryUrl?.takeIf { s.type == "Subtitle" && s.deliveryMethod == "External" } ?: return@mapNotNull null
        val mime = JellyfinProfile.mimeType(s.codec) ?: return@mapNotNull null
        val withKey = (base.trimEnd('/') + delivery).toHttpUrl().newBuilder()
            .apply { if (!hasKey(delivery)) addQueryParameter("ApiKey", token) }.build()
        Subtitle(withKey.toString(), mime, s.language, s.displayTitle)
    }

    private fun hasKey(path: String): Boolean = Regex("[?&](ApiKey|api_key)=", RegexOption.IGNORE_CASE).containsMatchIn(path)

    override suspend fun endPlayback(source: PlaybackSource): Outcome<Unit> = when (source) {
        is PlaybackSource.Hls -> http.touch(
            url("/Videos/ActiveEncodings", "deviceId" to JellyfinHttp.clean(identity.deviceId.value), "playSessionId" to source.sessionId),
            "DELETE",
        )
        is PlaybackSource.DirectPlay -> Outcome.Success(Unit)
    }

    /** Jellyfin's images need no token; the tag in the path keeps caches honest. */
    override fun artworkUrl(ref: ArtworkRef, size: ArtworkSize): String =
        (base.trimEnd('/') + ref.path).toHttpUrl().newBuilder()
            .addQueryParameter("fillWidth", size.width.toString())
            .addQueryParameter("fillHeight", size.height.toString())
            .addQueryParameter("quality", ARTWORK_QUALITY.toString())
            .build().toString()

    override suspend fun reportProgress(item: MediaRef, position: Duration, state: PlaybackState): Outcome<Unit> {
        val body = buildJsonObject {
            put("ItemId", item.id.value)
            put("PositionTicks", JellyfinMapper.toTicks(position))
            put("IsPaused", state == PlaybackState.PAUSED)
            put("CanSeek", true)
        }
        val path = if (state == PlaybackState.STOPPED) "/Sessions/Playing/Stopped" else "/Sessions/Playing/Progress"
        return http.touch(url(path), "POST", body)
    }

    override suspend fun markPlayed(item: MediaRef): Outcome<Unit> =
        http.touch(url("/UserPlayedItems/${item.id.value}", "userId" to userId), "POST")

    companion object {
        private const val TAG = "Jellyfin"
        private const val IDS_PER_REQUEST = 100
        private const val ARTWORK_QUALITY = 90
        private const val SHOW_FIELDS = "Genres,Studios,Overview,ChildCount,RecursiveItemCount,ProductionYear"
        private const val PLAYABLE_FIELDS = "Genres,Studios,Overview,ProductionYear,MediaSources"
    }
}
