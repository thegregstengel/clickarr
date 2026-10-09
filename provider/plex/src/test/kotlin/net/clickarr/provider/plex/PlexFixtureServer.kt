package net.clickarr.provider.plex

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Serves the JSON fixtures under src/test/resources/fixtures as if it were a Plex server and plex.tv.
 * Requests are matched on path (and a few query parameters); everything else is a 404 so an
 * unexpected endpoint shows up as a test failure, not a silent empty list.
 */
class PlexFixtureServer : AutoCloseable {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    var pinClaimed = false
    var failWithStatus: Int? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                failWithStatus?.let { return MockResponse().setResponseCode(it) }
                val path = request.requestUrl?.encodedPath ?: return notFound()
                val q = request.requestUrl
                val fixture = when {
                    path == "/" -> "root"
                    path == "/library/sections" -> "sections"
                    path == "/library/sections/1/all" && q?.queryParameter("type") == "1" -> "section-1-movies"
                    path == "/library/sections/2/all" && q?.queryParameter("type") == "2" -> "section-2-shows"
                    path == "/library/metadata/201/allLeaves" -> "show-201-allLeaves"
                    path == "/library/metadata/202/allLeaves" -> "show-202-allLeaves"
                    path == "/library/sections/1/collections" -> "section-1-collections"
                    path == "/library/sections/2/collections" -> "empty"
                    path == "/library/collections/301/children" -> "collection-301-children"
                    path == "/playlists" -> "playlists"
                    path == "/playlists/401/items" -> "playlist-401-items"
                    path.startsWith("/library/metadata/") -> return metadataLookup(path.removePrefix("/library/metadata/"))
                    path.startsWith("/video/:/transcode/universal/stop") -> return ok("{}")
                    path.startsWith("/:/timeline") -> return ok("{}")
                    path == "/api/v2/pins" && request.method == "POST" -> "plextv-pin-created"
                    path == "/api/v2/pins/987654" -> if (pinClaimed) "plextv-pin-claimed" else "plextv-pin-created"
                    path == "/api/v2/resources" -> "plextv-resources"
                    else -> return notFound()
                }
                return if (fixture == "empty") ok("""{"MediaContainer":{"size":0}}""") else ok(load(fixture))
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** /library/metadata/{a,b,c}: pull each item out of the fixtures by ratingKey. */
    private fun metadataLookup(keys: String): MockResponse {
        val wanted = keys.split(",")
        val all = listOf("section-1-movies", "section-2-shows", "show-201-allLeaves", "show-202-allLeaves")
            .flatMap { plexJson.decodeFromString(PlexResponse.serializer(), load(it)).container.metadata }
        val found = wanted.mapNotNull { k -> all.firstOrNull { it.ratingKey == k } }
        if (found.isEmpty()) return notFound()
        val body = plexJson.encodeToString(PlexResponse.serializer(), PlexResponse(PlexContainer(size = found.size, metadata = found)))
        return ok(body)
    }

    private fun load(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "missing fixture $name" }.bufferedReader().readText()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun notFound() = MockResponse().setResponseCode(404)

    override fun close() = server.shutdown()
}
