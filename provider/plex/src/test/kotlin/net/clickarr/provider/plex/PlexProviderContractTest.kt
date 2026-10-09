package net.clickarr.provider.plex

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.Redact
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.testing.MediaProviderContractTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class PlexProviderContractTest : MediaProviderContractTest() {
    private val fixtures = PlexFixtureServer()
    private val providerId = ProviderId("plex-test")
    private val identity = ClientIdentity("device-1", "Test TV", "0.0.1")

    private fun plex(): PlexProvider = PlexProvider(
        id = providerId,
        server = ServerInfo(providerId, ProviderKind.PLEX, "abc123machine", "WOPR", fixtures.baseUrl),
        token = "secret-token",
        identity = identity,
        client = OkHttpClient(),
    )

    override fun provider(): MediaProvider = plex()

    private fun ref(key: String) = MediaRef(providerId, NativeItemId(key))

    @AfterEach
    fun tearDown() = fixtures.close()

    @Test
    fun `every request carries the Plex identity headers and the token as a header`() = runTest {
        plex().libraries().value()
        val req = fixtures.requests.last()
        req.getHeader("X-Plex-Token") shouldBe "secret-token"
        req.getHeader("X-Plex-Client-Identifier") shouldBe "device-1"
        req.getHeader("X-Plex-Product") shouldBe "Clickarr"
        req.getHeader("Accept") shouldBe "application/json"
    }

    @Test
    fun `ping fills in machine identifier, name and version from the server`() = runTest {
        val info = plex().ping().value()
        info.serverIdentity shouldBe "abc123machine"
        info.name shouldBe "WOPR"
        info.version shouldBe "1.41.0.8992"
    }

    @Test
    fun `episodes come back in aired order even when Plex returns them out of order`() = runTest {
        val eps = plex().episodes(ref("201")).value()
        eps.map { it.title } shouldContainExactly listOf("Pilot", "Diversity Day", "The Dundies", "Sexual Harassment")
        eps.first().runtime shouldBe 1382000.let { kotlin.time.Duration.parse("${it}ms") }
        eps.first().showTitle shouldBe "The Office (US)"
        eps.first().seasonIndex shouldBe 1
    }

    @Test
    fun `runtime prefers the media file duration over the metadata duration`() = runTest {
        val pilot = plex().episodes(ref("201")).value().first()
        pilot.runtime.inWholeMilliseconds shouldBe 1382000L
    }

    @Test
    fun `library filters work client-side on genre, decade, year range, studio and are combined with AND`() = runTest {
        val p = plex()
        val lib = ref("1")
        suspend fun ids(filter: MediaFilter) = p.resolve(ProgrammingSource.Library(lib, filter)).value().map { it.ref.id.value }
        ids(MediaFilter(decadeStart = 1980)) shouldBe listOf("103", "104")
        ids(MediaFilter(genres = setOf("Sci-Fi"))) shouldBe listOf("103")
        val lotr = p.resolve(ProgrammingSource.Library(lib, MediaFilter(yearFrom = 2001, yearTo = 2002))).value()
        lotr.map { it.ref.id.value } shouldBe listOf("101", "102")
        ids(MediaFilter(studios = setOf("Warner Bros."))) shouldBe listOf("104")
        ids(MediaFilter(genres = setOf("Comedy"), decadeStart = 2000)) shouldBe emptyList()
    }

    @Test
    fun `a show library resolves to episodes of the shows that match the filter`() = runTest {
        val eps = plex().resolve(ProgrammingSource.Library(ref("2"), MediaFilter(genres = setOf("Comedy")))).value()
        eps.size shouldBe 6
        eps.map { it.ref.id.value }.take(4) shouldBe listOf("2011", "2012", "2021", "2022")
    }

    @Test
    fun `direct play when the device can decode, HLS transcode with server-side offset when it cannot`() = runTest {
        val p = plex()
        val direct = p.playbackSource(ref("104"), DeviceProfile.Conservative, 17.minutes).value()
        direct.shouldBeInstanceOf<PlaybackSource.DirectPlay>()
        direct.url shouldContain "/library/parts/2004/"
        direct.url shouldContain "X-Plex-Token=secret-token"
        direct.startAt shouldBe 17.minutes
        direct.mimeType shouldBe "video/mp4"

        val hls = p.playbackSource(ref("103"), DeviceProfile.Conservative, 17.minutes).value() // 4K HEVC TrueHD
        hls.shouldBeInstanceOf<PlaybackSource.Hls>()
        hls.url shouldContain "/video/:/transcode/universal/start.m3u8"
        hls.url shouldContain "offset=1020"
        hls.url shouldContain "session=${hls.sessionId}"
        hls.startAt shouldBe kotlin.time.Duration.ZERO
        p.endPlayback(hls).value()
        fixtures.requests.last().path!! shouldContain "/video/:/transcode/universal/stop?session=${hls.sessionId}"
    }

    @Test
    fun `a 4K HEVC file direct plays on a device that supports it`() = runTest {
        val fourK = DeviceProfile.Conservative.copy(
            maxWidth = 3840, maxHeight = 2160, supportsHevc = true, audioCodecs = setOf("truehd"), maxBitrateKbps = null,
        )
        plex().playbackSource(ref("103"), fourK, 0.minutes).value().shouldBeInstanceOf<PlaybackSource.DirectPlay>()
    }

    @Test
    fun `artwork URLs go through the photo transcoder with the requested size`() {
        val url = plex().artworkUrl(net.clickarr.core.model.ArtworkRef(providerId, "/library/metadata/101/thumb/1"), ArtworkSize.POSTER)
        url shouldContain "/photo/:/transcode"
        url shouldContain "width=300"
        url shouldContain "url=%2Flibrary%2Fmetadata%2F101%2Fthumb%2F1"
    }

    @Test
    fun `tokens in playback URLs are redacted by the logging facade`() = runTest {
        val direct = plex().playbackSource(ref("104"), DeviceProfile.Conservative, 0.minutes).value()
        Redact.apply(direct.url) shouldNotContain "secret-token"
    }

    @Test
    fun `HTTP 401 maps to Unauthorized and a dead server maps to Unreachable`() = runTest {
        fixtures.failWithStatus = 401
        (plex().libraries() as Outcome.Failure).error.shouldBeInstanceOf<ClickarrError.Unauthorized>()
        fixtures.failWithStatus = null
        val deadServer = ServerInfo(providerId, ProviderKind.PLEX, "x", "x", "http://127.0.0.1:1")
        val dead = PlexProvider(providerId, deadServer, "t", identity, OkHttpClient())
        (dead.libraries() as Outcome.Failure).error.shouldBeInstanceOf<ClickarrError.Unreachable>()
    }

    @Test
    fun `paging sends Plex container headers`() = runTest {
        plex().movies(ref("1"), net.clickarr.provider.api.Page(offset = 200, size = 50)).value()
        val req = fixtures.requests.last()
        req.getHeader("X-Plex-Container-Start") shouldBe "200"
        req.getHeader("X-Plex-Container-Size") shouldBe "50"
    }
}
