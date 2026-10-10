package net.clickarr.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import net.clickarr.core.common.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The Drive calls as the sync engine makes them, against a local server standing in for Google. */
class DriveAppDataTest {
    private val server = MockWebServer()
    private lateinit var drive: DriveAppData

    @BeforeEach
    fun start() {
        server.start()
        drive = DriveAppData(OkHttpClient(), { Outcome.Success("token-1") }, apiBase = server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `find reads the revision from the file properties and sends the bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"abc","appProperties":{"revision":"7"}}]}"""))
        val found = drive.find("household-state.json").shouldBeInstanceOf<Outcome.Success<DriveAppData.File?>>().value
        found?.id shouldBe "abc"
        found?.revision shouldBe 7L
        val request = server.takeRequest()
        request.getHeader("Authorization") shouldBe "Bearer token-1"
        request.path shouldContain "spaces=appDataFolder"
    }

    @Test
    fun `create uploads metadata and content as one related multipart body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"new","appProperties":{"revision":"1"}}"""))
        val created = drive.create("household-state.json", """{"revision":1}""", 1)
        val file = created.shouldBeInstanceOf<Outcome.Success<DriveAppData.File>>().value
        file.id shouldBe "new"
        val request = server.takeRequest()
        request.getHeader("Content-Type") shouldContain "multipart/related"
        val body = request.body.readUtf8()
        body shouldContain "appDataFolder"
        body shouldContain "\"revision\":1"
    }

    @Test
    fun `an expired sign-in is reported as unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        drive.download("abc").shouldBeInstanceOf<Outcome.Failure>().error.message shouldContain "sign in again"
    }
}
