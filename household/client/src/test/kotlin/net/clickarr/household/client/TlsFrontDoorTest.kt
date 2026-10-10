package net.clickarr.household.client

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.Household
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.core.model.HouseholdId
import net.clickarr.core.model.HouseholdState
import net.clickarr.household.coordinator.Coordinator
import net.clickarr.household.coordinator.InMemoryCoordinatorStore
import net.clickarr.household.coordinator.TlsFrontDoor
import net.clickarr.household.coordinator.coordinatorRoutes
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The coordinator behind the TLS front door, reached by a member that pins on first use. The certificate
 * comes from the JDK's keytool at test time, so no key material lives in the repository.
 */
class TlsFrontDoorTest {
    private val t0 = Instant.parse("2026-10-10T19:00:00Z")
    private val livingRoom = DeviceId("living-room")
    private lateinit var engine: EmbeddedServer<*, *>
    private lateinit var door: TlsFrontDoor
    private lateinit var fingerprint: String
    private lateinit var baseUrl: String

    @BeforeEach
    fun start() = runBlocking {
        val (keyStore, cert) = testIdentity()
        fingerprint = spkiFingerprint(cert)
        val state = HouseholdState(
            revision = 0, schedulerVersion = 1,
            household = Household(HouseholdId("h1"), "Home", livingRoom, t0),
            devices = listOf(HouseholdDevice(livingRoom, "Living Room", t0, t0)),
            servers = emptyList(), channels = emptyList(), lineups = emptyList(), favorites = emptySet(),
        )
        val coordinator = Coordinator(state, InMemoryCoordinatorStore(), Clock { t0 }, fingerprint = { fingerprint })
        coordinator.start()
        engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") { coordinatorRoutes(coordinator) }.also { it.start(wait = false) }
        val backendPort = engine.engine.resolvedConnectors().first().port
        door = TlsFrontDoor(TlsFrontDoor.sslContext(keyStore, ALIAS, PASSWORD), backendPort)
        baseUrl = "https://127.0.0.1:${door.start(0)}"
    }

    @AfterEach
    fun stop() {
        door.stop()
        engine.stop(100, 500)
    }

    @Test
    fun `first contact records the certificate and the coordinator states the same fingerprint`() = runBlocking {
        val trust = TrustOnFirstUse(expected = null)
        val client = HouseholdClient(OkHttpClient().pinnedTo(trust), baseUrl) { null }
        val info = client.info().shouldBeInstanceOf<Outcome.Success<*>>()
        trust.observed shouldBe fingerprint
        (info.value as net.clickarr.household.protocol.InfoResponse).fingerprint shouldBe fingerprint
    }

    @Test
    fun `a pinned member refuses a coordinator with a different certificate`() = runBlocking {
        val trust = TrustOnFirstUse(expected = "00".repeat(32))
        val client = HouseholdClient(OkHttpClient().pinnedTo(trust), baseUrl) { null }
        client.info().shouldBeInstanceOf<Outcome.Failure>()
        trust.observed shouldBe null
    }

    @Test
    fun `a pinned member connects when the certificate matches`() = runBlocking {
        val client = HouseholdClient(OkHttpClient().pinnedTo(TrustOnFirstUse(expected = fingerprint)), baseUrl) { null }
        client.info().shouldBeInstanceOf<Outcome.Success<*>>()
    }

    private fun testIdentity(): Pair<KeyStore, X509Certificate> {
        val dir = Files.createTempDirectory("clickarr-tls").toFile()
        val p12 = File(dir, "test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1", "-alias", ALIAS,
            "-storetype", "PKCS12", "-keystore", p12.path, "-storepass", String(PASSWORD), "-keypass", String(PASSWORD),
            "-dname", "CN=Clickarr Test", "-validity", "2",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, PASSWORD) } }
        return keyStore to (keyStore.getCertificate(ALIAS) as X509Certificate)
    }

    private companion object {
        const val ALIAS = "test"
        val PASSWORD = "changeit".toCharArray()
    }
}
