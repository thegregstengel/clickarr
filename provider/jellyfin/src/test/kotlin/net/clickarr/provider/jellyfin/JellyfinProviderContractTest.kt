package net.clickarr.provider.jellyfin

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
import net.clickarr.core.model.ArtworkRef
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.CaptionRequest
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.api.PlaybackState
import net.clickarr.provider.jellyfin.fixtures.JellyfinFixtureServer
import net.clickarr.provider.testing.MediaProviderContractTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JellyfinProviderContractTest : MediaProviderContractTest() {
    private val fixtures = JellyfinFixtureServer()
    private val providerId = ProviderId("f1x7ure0000000000000000000000001")
    private val identity = ClientIdentity("device-1", "Test TV", "0.0.1")

    private fun jellyfin(token: String = JellyfinFixtureServer.TOKEN): JellyfinProvider = JellyfinProvider(
        id = providerId,
        server = ServerInfo(providerId, ProviderKind.JELLYFIN, providerId.value, "Den", fixtures.baseUrl),
        token = token,
        userId = "user-viewer",
        identity = identity,
        client = OkHttpClient(),
    )

    override fun provider(): MediaProvider = jellyfin()

    private fun ref(id: String) = MediaRef(providerId, NativeItemId(id))

    @AfterEach
    fun tearDown() = fixtures.close()

    @Test
    fun `every request carries the MediaBrowser authorization with the token`() = runTest {
        jellyfin().libraries().value()
        val auth = fixtures.requests.last().getHeader("Authorization").orEmpty()
        auth shouldContain "MediaBrowser Client=\"Clickarr\""
        auth shouldContain "DeviceId=\"device-1\""
        auth shouldContain "Token=\"fixture-token\""
    }

    @Test
    fun `ping fills in the server id, name and version`() = runTest {
        val info = jellyfin().ping().value()
        info.serverIdentity shouldBe "f1x7ure0000000000000000000000001"
        info.name shouldBe "Den"
        info.version shouldBe "12.2.0"
    }

    @Test
    fun `episodes come back in aired order even when the server sends them reversed`() = runTest {
        val eps = jellyfin().episodes(ref("series-office")).value()
        eps.map { it.title } shouldContainExactly listOf("Pilot", "Diversity Day", "The Dundies", "Sexual Harassment")
        eps.first().runtime shouldBe 22.minutes
        eps.first().showTitle shouldBe "The Office (US)"
        eps.first().artwork.poster?.path shouldBe "/Items/series-office/Images/Primary?tag=tag-series-office"
    }

    @Test
    fun `library filters work on genre, decade, year range, and studio`() = runTest {
        val p = jellyfin()
        suspend fun ids(filter: MediaFilter) =
            p.resolve(ProgrammingSource.Library(ref("lib-movies"), filter)).value().map { it.ref.id.value }
        ids(MediaFilter(decadeStart = 1980)) shouldBe listOf("movie-bttf", "movie-goonies")
        ids(MediaFilter(genres = setOf("science fiction"))) shouldBe listOf("movie-bttf")
        ids(MediaFilter(yearFrom = 2001, yearTo = 2002)) shouldBe listOf("movie-fellowship", "movie-towers")
        ids(MediaFilter(studios = setOf("Warner Bros."))) shouldBe listOf("movie-goonies")
    }

    @Test
    fun `a collection keeps its order and a playlist resolves its entries`() = runTest {
        val p = jellyfin()
        p.collections(ref("lib-movies")).value().map { it.name } shouldBe listOf("The Lord of the Rings")
        p.resolve(ProgrammingSource.Collection(ref("boxset-lotr"))).value().map { it.ref.id.value } shouldBe
            listOf("movie-fellowship", "movie-towers")
        p.resolve(ProgrammingSource.Playlist(ref("playlist-80s"))).value().map { it.ref.id.value } shouldBe
            listOf("movie-bttf", "movie-goonies")
    }

    @Test
    fun `direct play streams the file with the authorization header and the offset for the player`() = runTest {
        val direct = jellyfin().playbackSource(ref("movie-goonies"), DeviceProfile.Conservative, 17.minutes).value()
        direct.shouldBeInstanceOf<PlaybackSource.DirectPlay>()
        direct.url shouldContain "/Videos/movie-goonies/stream?static=true"
        direct.url shouldNotContain "fixture-token"
        direct.headers["Authorization"].orEmpty() shouldContain "Token=\"fixture-token\""
        direct.startAt shouldBe 17.minutes
        direct.mimeType shouldBe "video/mp4"
    }

    @Test
    fun `direct play hands text subtitles to the player as separate files`() = runTest {
        val direct = jellyfin().playbackSource(ref("movie-goonies"), DeviceProfile.Conservative, 0.minutes).value()
        direct.shouldBeInstanceOf<PlaybackSource.DirectPlay>()
        val sub = direct.subtitles.single()
        sub.url shouldContain "/Subtitles/2/0/Stream.srt"
        sub.url shouldContain "ApiKey=fixture-token"
        sub.mimeType shouldBe "application/x-subrip"
        sub.language shouldBe "eng"
    }

    @Test
    fun `a 4K HEVC file transcodes on a basic TV, and captions on burn in the image subtitle`() = runTest {
        val p = jellyfin()
        val on = p.playbackSource(ref("movie-bttf"), DeviceProfile.Conservative, 10.minutes, CaptionRequest("eng")).value()
        on.shouldBeInstanceOf<PlaybackSource.Hls>()
        on.url shouldContain "/videos/movie-bttf/master.m3u8"
        on.url shouldContain "SubtitleStreamIndex=2"
        on.url shouldContain "SubtitleMethod=Encode"
        on.startAt shouldBe 10.minutes
        val off = p.playbackSource(ref("movie-bttf"), DeviceProfile.Conservative, 0.minutes).value()
        off.url shouldNotContain "SubtitleStreamIndex"
        off.url shouldNotContain "SubtitleMethod"
        p.endPlayback(off).value()
        val stop = fixtures.requests.last()
        stop.method shouldBe "DELETE"
        stop.path!! shouldContain "/Videos/ActiveEncodings?deviceId=device-1&playSessionId=${(off as PlaybackSource.Hls).sessionId}"
    }

    @Test
    fun `a 4K HEVC file direct plays on a TV that supports it`() = runTest {
        val fourK = DeviceProfile.Conservative.copy(
            maxWidth = 3840, maxHeight = 2160, supportsHevc = true, containers = setOf("mp4", "mkv"),
            audioCodecs = setOf("aac", "truehd"), maxBitrateKbps = null,
        )
        jellyfin().playbackSource(ref("movie-bttf"), fourK, 0.minutes).value().shouldBeInstanceOf<PlaybackSource.DirectPlay>()
    }

    @Test
    fun `a TrueHD default track does not force a transcode when the file also carries AC3`() = runTest {
        val src = jellyfin().playbackSource(ref("ep-parks-101"), DeviceProfile.Conservative, 0.minutes).value()
        src.shouldBeInstanceOf<PlaybackSource.DirectPlay>()
        src.mimeType shouldBe "video/x-matroska"
    }

    @Test
    fun `artwork URLs need no token and carry the requested size`() {
        val url = jellyfin().artworkUrl(ArtworkRef(providerId, "/Items/movie-towers/Images/Primary?tag=abc"), ArtworkSize.POSTER)
        url shouldContain "/Items/movie-towers/Images/Primary?tag=abc"
        url shouldContain "fillWidth=300"
        url shouldNotContain "fixture-token"
    }

    @Test
    fun `watch reports go to the session endpoints and marking played to the user's played items`() = runTest {
        val p = jellyfin()
        p.reportProgress(ref("ep-office-101"), 5.minutes, PlaybackState.PLAYING).value()
        fixtures.requests.last().path!! shouldContain "/Sessions/Playing/Progress"
        fixtures.requests.last().body.readUtf8() shouldContain "\"PositionTicks\":3000000000"
        p.reportProgress(ref("ep-office-101"), 5.minutes, PlaybackState.STOPPED).value()
        fixtures.requests.last().path!! shouldContain "/Sessions/Playing/Stopped"
        p.markPlayed(ref("ep-office-101")).value()
        fixtures.requests.last().path!! shouldContain "/UserPlayedItems/ep-office-101?userId=user-viewer"
    }

    @Test
    fun `tokens in playback URLs are redacted by the logging facade`() = runTest {
        val hls = jellyfin().playbackSource(ref("movie-bttf"), DeviceProfile.Conservative, 0.minutes).value()
        Redact.apply(hls.url) shouldNotContain "fixture-token"
    }

    @Test
    fun `a wrong token is Unauthorized and a dead server is Unreachable`() = runTest {
        (jellyfin(token = "wrong").libraries() as Outcome.Failure).error.shouldBeInstanceOf<ClickarrError.Unauthorized>()
        val dead = JellyfinProvider(
            providerId, ServerInfo(providerId, ProviderKind.JELLYFIN, "x", "x", "http://127.0.0.1:1"), "t", "u", identity, OkHttpClient(),
        )
        (dead.libraries() as Outcome.Failure).error.shouldBeInstanceOf<ClickarrError.Unreachable>()
    }

    @Test
    fun `paging asks for startIndex and limit`() = runTest {
        val page = jellyfin().movies(ref("lib-movies"), Page(offset = 2, size = 1)).value()
        page.items.size shouldBe 1
        page.total shouldBe 4
        fixtures.requests.last().path!! shouldContain "startIndex=2&limit=1"
    }
}
