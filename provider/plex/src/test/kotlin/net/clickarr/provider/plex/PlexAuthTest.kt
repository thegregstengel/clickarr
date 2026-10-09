package net.clickarr.provider.plex

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.ClientIdentity
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class PlexAuthTest {
    private val fixtures = PlexFixtureServer()
    private val auth = PlexAuth(OkHttpClient(), ClientIdentity("device-1", "Test TV", "0.0.1"), plexTvBase = fixtures.baseUrl)

    private fun <T> Outcome<T>.value(): T = (this as Outcome.Success).value

    @AfterEach
    fun tearDown() = fixtures.close()

    @Test
    fun `pin flow - create, poll until claimed, then list servers with local connections first`() = runTest {
        val pin = auth.createPin().value()
        pin.code shouldBe "ABCD"
        fixtures.requests.last().method shouldBe "POST"
        auth.checkPin(pin).value().shouldBeNull()
        fixtures.pinClaimed = true
        val token = auth.checkPin(pin).value()
        token shouldBe "account-token-xyz"

        val servers = auth.servers(token!!).value()
        servers.size shouldBe 1
        val wopr = servers.single()
        wopr.server.name shouldBe "WOPR"
        wopr.server.serverIdentity shouldBe "abc123machine"
        wopr.token shouldBe "server-token-123"
        wopr.server.urls shouldBe listOf("https://192-168-1-20.abc123.plex.direct:32400", "https://203-0-113-10.abc123.plex.direct:32400")
        fixtures.requests.last().getHeader("X-Plex-Token") shouldBe "account-token-xyz"
    }
}
