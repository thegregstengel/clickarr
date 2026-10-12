package net.clickarr.provider.jellyfin

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.ProviderKind
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.jellyfin.fixtures.JellyfinFixtureServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JellyfinAuthTest {
    private val fixtures = JellyfinFixtureServer()
    private val auth = JellyfinAuth(OkHttpClient(), ClientIdentity("device-1", "Living Room “TV”", "0.0.1"))

    private fun <T> Outcome<T>.value(): T = (this as? Outcome.Success)?.value ?: error("expected success, got $this")

    @AfterEach
    fun tearDown() = fixtures.close()

    @Test
    fun `Quick Connect shows a code, waits for approval, then signs in`() = runTest {
        val base = fixtures.baseUrl
        auth.quickConnectEnabled(base).value() shouldBe true
        val request = auth.startQuickConnect(base).value()
        request.code shouldBe JellyfinFixtureServer.QC_CODE
        auth.approved(base, request).value() shouldBe false
        fixtures.approveQuickConnect()
        auth.approved(base, request).value() shouldBe true
        val signIn = auth.finishQuickConnect(base, request).value()
        signIn.credentials.kind shouldBe ProviderKind.JELLYFIN
        signIn.credentials.values[JellyfinProviderFactory.TOKEN] shouldBe JellyfinFixtureServer.TOKEN
        signIn.credentials.values[JellyfinProviderFactory.USER_ID] shouldBe "user-viewer"
        request.toString() shouldNotContain "qc-secret"
    }

    @Test
    fun `username and password sign in, and a wrong password is Unauthorized`() = runTest {
        auth.password(fixtures.baseUrl, JellyfinFixtureServer.USER, JellyfinFixtureServer.PASSWORD).value().userName shouldBe "Viewer"
        val wrong = auth.password(fixtures.baseUrl, JellyfinFixtureServer.USER, "nope") as Outcome.Failure
        wrong.error.shouldBeInstanceOf<ClickarrError.Unauthorized>()
        JellyfinAuth.explain(wrong.error) shouldBe "Jellyfin did not accept that username and password."
    }

    @Test
    fun `a device name with curly quotes still makes a valid header`() = runTest {
        auth.quickConnectEnabled(fixtures.baseUrl).value()
        fixtures.requests.last().getHeader("Authorization")!! shouldNotContain "“"
    }

    @Test
    fun `typed addresses become server URLs`() {
        JellyfinAuth.normalize("192.168.2.80") shouldBe "http://192.168.2.80:8096"
        JellyfinAuth.normalize("192.168.2.80:30013") shouldBe "http://192.168.2.80:30013"
        JellyfinAuth.normalize("https://media.example.com/jellyfin/") shouldBe "https://media.example.com/jellyfin"
        JellyfinAuth.normalize("http://jf.local") shouldBe "http://jf.local"
        JellyfinAuth.normalize("  ") shouldBe null
    }
}
