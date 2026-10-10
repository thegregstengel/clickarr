package net.clickarr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock as KxClock
import kotlinx.datetime.Instant
import net.clickarr.core.common.Clock
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.Household
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.core.model.HouseholdId
import net.clickarr.core.model.HouseholdState
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.Lineups
import net.clickarr.household.client.spkiFingerprint
import net.clickarr.household.coordinator.Coordinator
import net.clickarr.household.coordinator.TlsFrontDoor
import net.clickarr.household.coordinator.InMemoryCoordinatorStore
import net.clickarr.household.coordinator.coordinatorRoutes

/**
 * A second "TV" living inside the test process: a coordinator with one movie channel built from the
 * Plex fixture ids, so the app can join it by address and PIN and end up with that channel.
 */
class TestCoordinator : AutoCloseable {
    private val livingRoom = DeviceId("living-room")
    private val clock = Clock { KxClock.System.now() }
    val coordinator: Coordinator
    private val server: EmbeddedServer<*, *>
    private val door: TlsFrontDoor
    val baseUrl: String
    val fingerprint: String

    init {
        val now = clock.now()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generateKey()
        fingerprint = spkiFingerprint(keyStore.getCertificate(ALIAS) as X509Certificate)
        coordinator = Coordinator(initialState(now), InMemoryCoordinatorStore(), clock, fingerprint = { fingerprint })
        runBlocking { coordinator.start() }
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") { coordinatorRoutes(coordinator) }
        runBlocking { server.startSuspend(wait = false) }
        val backendPort = runBlocking { server.engine.resolvedConnectors().first().port }
        // The same TLS front door the app uses, so the member side pairs over TLS and pins this certificate.
        door = TlsFrontDoor(TlsFrontDoor.sslContext(keyStore, ALIAS, null), backendPort)
        val port = door.start(0)
        baseUrl = "https://127.0.0.1:$port"
    }

    private fun generateKey() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
            .setCertificateSubject(X500Principal("CN=Clickarr Test Coordinator"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun initialState(now: Instant): HouseholdState {
        val plex = ProviderId("abc123machine") // the fixture server's identity, same as the app's provider id
        val channelId = ChannelId("ch-movies")
        val lineup = Lineups.create(
            LineupSnapshotId("lineup-movies"), channelId,
            listOf(
                LineupEntry(MediaRef(plex, NativeItemId("104")), 114.minutes, "The Goonies", "1985"),
                LineupEntry(MediaRef(plex, NativeItemId("103")), 116.minutes, "Back to the Future", "1985"),
            ),
            now,
        )
        val channel = Channel(
            id = channelId, number = 20, name = "Movies", source = ProgrammingSource.Explicit(lineup.entries.map { it.ref }),
            order = OrderingMode.SEQUENTIAL, slotRounding = 30.minutes, seed = 20L, lineup = lineup.id, anchor = now,
        )
        return HouseholdState(
            revision = 3, schedulerVersion = 1,
            household = Household(HouseholdId("h-test"), "Test Home", livingRoom, now),
            devices = listOf(HouseholdDevice(livingRoom, "Living Room", now, now)),
            servers = emptyList(), channels = listOf(channel), lineups = listOf(lineup), favorites = emptySet(),
        )
    }

    fun pin(): String = coordinator.beginAcceptingJoins().pin

    override fun close() {
        door.stop()
        server.stop(100, 500)
    }

    private companion object {
        const val ALIAS = "clickarr-test-coordinator"
        const val DAY_MS = 86_400_000L
    }
}
