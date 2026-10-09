package net.clickarr.provider.plex.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Serves the JSON fixtures under resources/fixtures as if it were a Plex server and plex.tv.
 * Requests are matched on path (and a few query parameters); everything else is a 404 so an
 * unexpected endpoint shows up as a failure, not a silent empty list.
 *
 * The fixtures are synthetic (see resources/fixtures/README.md).
 */
class PlexFixtureServer : AutoCloseable {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    var pinClaimed = false
    var failWithStatus: Int? = null

    /** Optional bytes to serve for media part URLs (/library/parts/...). Null means 404. */
    var mediaBody: ByteArray? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val forced = failWithStatus
                val url = request.requestUrl
                return when {
                    forced != null -> MockResponse().setResponseCode(forced)
                    url == null -> notFound()
                    else -> respond(url.encodedPath, url.queryParameter("type"), request.method)
                }
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** Exact-path fixtures. Query type is appended for the two library listings. */
    private val static: Map<String, String> = mapOf(
        "/" to "root",
        "/library/sections" to "sections",
        "/library/sections/1/all?type=1" to "section-1-movies",
        "/library/sections/2/all?type=2" to "section-2-shows",
        "/library/metadata/201/allLeaves" to "show-201-allLeaves",
        "/library/metadata/202/allLeaves" to "show-202-allLeaves",
        "/library/sections/1/collections" to "section-1-collections",
        "/library/sections/2/collections" to "empty",
        "/library/collections/301/children" to "collection-301-children",
        "/playlists" to "playlists",
        "/playlists/401/items" to "playlist-401-items",
        "/api/v2/pins" to "plextv-pin-created",
        "/api/v2/resources" to "plextv-resources",
    )

    private fun respond(path: String, type: String?, method: String?): MockResponse {
        val key = if (type != null && path.endsWith("/all")) "$path?type=$type" else path
        val fixture = when {
            path == "/api/v2/pins/987654" -> if (pinClaimed) "plextv-pin-claimed" else "plextv-pin-created"
            path == "/api/v2/pins" && method != "POST" -> null
            else -> static[key]
        }
        return when {
            fixture == "empty" -> ok("""{"MediaContainer":{"size":0}}""")
            fixture != null -> ok(load(fixture))
            else -> dynamic(path)
        }
    }

    private fun dynamic(path: String): MockResponse = when {
        path.startsWith("/library/metadata/") -> metadataLookup(path.removePrefix("/library/metadata/"))
        path.startsWith("/library/parts/") -> media()
        path.startsWith("/video/:/transcode/universal/stop") || path.startsWith("/:/timeline") -> ok("{}")
        else -> notFound()
    }

    /** /library/metadata/{a,b,c}: pull each item out of the fixtures by ratingKey. */
    private fun metadataLookup(keys: String): MockResponse {
        val wanted = keys.split(",")
        val all: List<JsonObject> = listOf("section-1-movies", "section-2-shows", "show-201-allLeaves", "show-202-allLeaves")
            .flatMap { name ->
                json.parseToJsonElement(load(name)).jsonObject["MediaContainer"]!!.jsonObject["Metadata"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
            }
        val found = wanted.mapNotNull { k -> all.firstOrNull { it["ratingKey"]?.jsonPrimitive?.content == k } }
        if (found.isEmpty()) return notFound()
        val body = buildJsonObject {
            put(
                "MediaContainer",
                buildJsonObject {
                    put("size", JsonPrimitive(found.size))
                    put("Metadata", JsonArray(found))
                },
            )
        }
        return ok(body.toString())
    }

    private fun media(): MockResponse {
        val bytes = mediaBody ?: return notFound()
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4")
            .setBody(okio.Buffer().write(bytes))
    }

    private fun load(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "missing fixture $name" }.bufferedReader().readText()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun notFound() = MockResponse().setResponseCode(404)

    override fun close() = server.shutdown()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
