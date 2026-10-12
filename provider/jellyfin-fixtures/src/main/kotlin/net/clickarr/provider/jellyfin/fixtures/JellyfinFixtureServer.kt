package net.clickarr.provider.jellyfin.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A Jellyfin server for tests, serving [JellyfinCatalog]. Sign-in works with Quick Connect (approve with
 * [approveQuickConnect]) or username `viewer` and password `fixture-password`; every other call needs the token it
 * hands out. Anything unexpected is a 404, so a wrong endpoint fails loudly instead of returning an empty list.
 */
@Suppress("TooManyFunctions")
class JellyfinFixtureServer : AutoCloseable {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    var failWithStatus: Int? = null
    var quickConnectApproved = false

    /** Bytes served for direct-play stream URLs. Null means 404. */
    var mediaBody: ByteArray? = null
    private var sessions = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val forced = failWithStatus
                val url = request.requestUrl ?: return notFound()
                return when {
                    forced != null -> MockResponse().setResponseCode(forced)
                    url.encodedPath in OPEN -> open(url, request)
                    !signedIn(request) -> MockResponse().setResponseCode(401)
                    else -> route(url, request)
                }
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun approveQuickConnect() {
        quickConnectApproved = true
    }

    private fun signedIn(request: RecordedRequest): Boolean {
        val auth = request.getHeader("Authorization").orEmpty()
        return "Token=\"$TOKEN\"" in auth || request.requestUrl?.queryParameter("ApiKey") == TOKEN || isImage(request)
    }

    private fun isImage(request: RecordedRequest) = request.requestUrl?.encodedPath?.contains("/Images/") == true

    private fun open(url: HttpUrl, request: RecordedRequest): MockResponse = when (url.encodedPath) {
        "/System/Info/Public" -> ok(systemInfo())
        "/QuickConnect/Enabled" -> ok(JsonPrimitive(true))
        "/QuickConnect/Initiate" -> ok(quickConnect())
        "/QuickConnect/Connect" -> if (url.queryParameter("secret") == QC_SECRET) ok(quickConnect()) else notFound()
        "/Users/AuthenticateWithQuickConnect" -> if (quickConnectApproved) ok(authResult()) else MockResponse().setResponseCode(401)
        "/Users/AuthenticateByName" -> {
            val body = runCatching { json.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrNull()
            val ok = body?.get("Username")?.jsonPrimitive?.content == USER && body["Pw"]?.jsonPrimitive?.content == PASSWORD
            if (ok) ok(authResult()) else MockResponse().setResponseCode(401)
        }
        else -> notFound()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun route(url: HttpUrl, request: RecordedRequest): MockResponse {
        val path = url.encodedPath
        val segments = url.pathSegments
        return when {
            path == "/System/Info" -> ok(systemInfo())
            path == "/UserViews" -> ok(list(JellyfinCatalog.libraries.map { (id, type) -> library(id, type) }))
            path == "/Items" -> ok(items(url))
            path.endsWith("/PlaybackInfo") -> ok(playbackInfo(segments[1], request))
            segments.size == 3 && segments[0] == "Shows" && segments[2] == "Episodes" ->
                ok(list(episodesOf(segments[1]).reversed().map(::item)))
            segments.size == 3 && segments[0] == "Playlists" && segments[2] == "Items" ->
                ok(list(childrenOf(segments[1]).map(::item)))
            path.startsWith("/Sessions/Playing") || path.startsWith("/UserPlayedItems/") -> noContent()
            path == "/Videos/ActiveEncodings" && request.method == "DELETE" -> noContent()
            path.contains("/Subtitles/") -> MockResponse().setResponseCode(200).setBody(SRT)
            segments.size == 3 && segments[0] == "Videos" && segments[2] == "stream" -> media()
            isImage(request) -> thumb()
            else -> notFound()
        }
    }

    /** `/Items`: by ids, children of a collection, or a library listing narrowed by type, paged. */
    private fun items(url: HttpUrl): JsonObject {
        val ids = url.queryParameter("ids")?.split(",")
        val parent = url.queryParameter("parentId")
        val types = url.queryParameter("includeItemTypes")?.split(",")
        val picked: List<FxItem> = when {
            ids != null -> ids.mapNotNull(JellyfinCatalog::byId)
            parent != null && parent in JellyfinCatalog.children && types == null -> childrenOf(parent)
            else -> JellyfinCatalog.items
                .filter { parent == null || it.parent == parent }
                .filter { types == null || it.type in types }
                .sortedBy { it.name.removePrefix("The ") }
        }
        val start = url.queryParameter("startIndex")?.toIntOrNull() ?: 0
        val limit = url.queryParameter("limit")?.toIntOrNull() ?: picked.size
        return list(picked.drop(start).take(limit).map(::item), total = picked.size)
    }

    private fun episodesOf(series: String) = JellyfinCatalog.items.filter { it.type == "Episode" && it.seriesId == series }

    private fun childrenOf(parent: String) = JellyfinCatalog.children[parent].orEmpty().mapNotNull(JellyfinCatalog::byId)

    /** The playback decision: direct play when the profile lists the file's container and codecs and its height fits. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun playbackInfo(id: String, request: RecordedRequest): JsonObject {
        val item = JellyfinCatalog.byId(id) ?: return buildJsonObject { putJsonArray("MediaSources") {} }
        val body = runCatching { json.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrNull()
        val profile = body?.get("DeviceProfile")?.jsonObject
        val direct = profile?.get("DirectPlayProfiles")?.jsonArray?.firstOrNull()?.jsonObject
        fun listed(key: String, value: String) = direct?.get(key)?.jsonPrimitive?.content.orEmpty().split(",").contains(value)
        val maxHeight = profile?.get("CodecProfiles")?.jsonArray?.firstOrNull()?.jsonObject?.get("Conditions")?.jsonArray
            ?.map { it.jsonObject }?.firstOrNull { it["Property"]?.jsonPrimitive?.content == "Height" }
            ?.get("Value")?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE
        // Like the real server, judge only the default audio track and say why when it cannot direct play.
        val reasons = buildList {
            if (!listed("Container", item.container)) add("ContainerNotSupported")
            if (!listed("VideoCodec", item.video)) add("VideoCodecNotSupported")
            if (item.height > maxHeight) add("VideoResolutionNotSupported")
            if (!listed("AudioCodec", item.audio)) add("AudioCodecNotSupported")
        }
        val canDirect = reasons.isEmpty()
        val session = "ps-${++sessions}"
        val subtitleIndex = body?.get("SubtitleStreamIndex")?.jsonPrimitive?.int ?: -1
        return buildJsonObject {
            put("PlaySessionId", session)
            putJsonArray("MediaSources") {
                add(
                    mediaSource(item).toMutableMap().apply {
                        put("SupportsDirectPlay", JsonPrimitive(canDirect))
                        if (!canDirect) {
                            put(
                                "TranscodingUrl",
                                JsonPrimitive(
                                    "/videos/${item.id}/master.m3u8?DeviceId=fixture&MediaSourceId=${item.id}&PlaySessionId=$session" +
                                        "&ApiKey=$TOKEN&SubtitleStreamIndex=$subtitleIndex&SubtitleMethod=Hls" +
                                        "&TranscodeReasons=${reasons.joinToString(",")}",
                                ),
                            )
                        }
                    }.let(::JsonObject),
                )
            }
        }
    }

    private fun library(id: String, type: String) = buildJsonObject {
        put("Id", id)
        put("Name", JellyfinCatalog.libraryNames[id])
        put("Type", "CollectionFolder")
        put("CollectionType", type)
    }

    private fun item(i: FxItem): JsonObject = buildJsonObject {
        put("Id", i.id)
        put("Name", i.name)
        put("Type", i.type)
        i.year?.let { put("ProductionYear", it) }
        putJsonArray("Genres") { i.genres.forEach { add(it) } }
        putJsonArray("Studios") { i.studio?.let { s -> addJsonObject { put("Name", s) } } }
        put("Overview", "A synthetic ${i.type.lowercase()} for tests.")
        put("ImageTags", buildJsonObject { put("Primary", "tag-${i.id}") })
        putJsonArray("BackdropImageTags") { add("bd-${i.id}") }
        if (i.minutes > 0) put("RunTimeTicks", i.minutes * TICKS_PER_MINUTE)
        when (i.type) {
            "Series" -> {
                put("ChildCount", episodesOf(i.id).map { it.season }.distinct().size)
                put("RecursiveItemCount", episodesOf(i.id).size)
            }
            "BoxSet", "Playlist" -> put("ChildCount", childrenOf(i.id).size)
            "Episode" -> {
                put("SeriesId", i.seriesId)
                put("SeriesName", JellyfinCatalog.byId(i.seriesId.orEmpty())?.name)
                put("SeriesPrimaryImageTag", "tag-${i.seriesId}")
                put("SeasonId", "${i.seriesId}-s${i.season}")
                put("ParentIndexNumber", i.season)
                put("IndexNumber", i.episode)
                put("ParentBackdropItemId", i.seriesId)
                putJsonArray("ParentBackdropImageTags") { add("bd-${i.seriesId}") }
            }
        }
        if (i.type == "Movie" || i.type == "Episode") putJsonArray("MediaSources") { add(mediaSource(i)) }
    }

    private fun mediaSource(i: FxItem): JsonObject = buildJsonObject {
        put("Id", i.id)
        put("Container", i.container)
        put("Bitrate", BITRATE)
        put("RunTimeTicks", i.minutes * TICKS_PER_MINUTE)
        put("SupportsDirectPlay", true)
        putJsonArray("MediaStreams") {
            addJsonObject {
                put("Type", "Video")
                put("Index", 0)
                put("Codec", i.video)
                put("Width", i.width)
                put("Height", i.height)
            }
            addJsonObject {
                put("Type", "Audio")
                put("Index", 1)
                put("Codec", i.audio)
                put("Language", "eng")
            }
            i.altAudio?.let { codec ->
                addJsonObject {
                    put("Type", "Audio")
                    put("Index", ALT_AUDIO_INDEX)
                    put("Codec", codec)
                    put("Language", "eng")
                }
            }
            i.subtitles.forEach { s ->
                addJsonObject {
                    put("Type", "Subtitle")
                    put("Index", s.index)
                    put("Codec", s.codec)
                    put("Language", s.language)
                    put("DisplayTitle", "English")
                    put("IsExternal", s.external)
                    put("IsTextSubtitleStream", s.codec == "subrip")
                    if (s.codec == "subrip") {
                        put("DeliveryMethod", "External")
                        put("DeliveryUrl", "/Videos/${i.id}/${i.id}/Subtitles/${s.index}/0/Stream.srt")
                    }
                }
            }
        }
    }

    private fun list(items: List<JsonObject>, total: Int = items.size) = buildJsonObject {
        put("Items", JsonArray(items))
        put("TotalRecordCount", total)
        put("StartIndex", 0)
    }

    private fun systemInfo() = buildJsonObject {
        put("Id", JellyfinCatalog.SERVER_ID)
        put("ServerName", "Den")
        put("Version", "12.2.0")
        put("ProductName", "Jellyfin Server")
    }

    private fun quickConnect() = buildJsonObject {
        put("Secret", QC_SECRET)
        put("Code", QC_CODE)
        put("Authenticated", quickConnectApproved)
    }

    private fun authResult() = buildJsonObject {
        put("AccessToken", TOKEN)
        put(
            "User",
            buildJsonObject {
                put("Id", "user-viewer")
                put("Name", "Viewer")
            },
        )
        put("ServerId", JellyfinCatalog.SERVER_ID)
    }

    private fun media(): MockResponse {
        val bytes = mediaBody ?: return notFound()
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(bytes))
    }

    private fun thumb(): MockResponse {
        val bytes = javaClass.getResourceAsStream("/jellyfin-fixtures/thumb.png")?.readBytes() ?: return notFound()
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes))
    }

    private fun ok(body: kotlinx.serialization.json.JsonElement) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun noContent() = MockResponse().setResponseCode(204)

    private fun notFound() = MockResponse().setResponseCode(404)

    override fun close() = server.shutdown()

    companion object {
        const val TOKEN = "fixture-token"
        const val USER = "viewer"
        const val PASSWORD = "fixture-password"
        const val QC_CODE = "424242"
        private const val QC_SECRET = "qc-secret"
        private const val TICKS_PER_MINUTE = 600_000_000L
        private const val BITRATE = 8_000_000
        private const val ALT_AUDIO_INDEX = 5
        private const val SRT = "1\n00:00:01,000 --> 00:00:03,000\nHello from the fixture.\n"
        private val OPEN = setOf(
            "/System/Info/Public", "/QuickConnect/Enabled", "/QuickConnect/Initiate", "/QuickConnect/Connect",
            "/Users/AuthenticateWithQuickConnect", "/Users/AuthenticateByName",
        )
        private val json = Json { ignoreUnknownKeys = true }
    }
}
