package net.clickarr

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
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
import net.clickarr.household.coordinator.Coordinator
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
    val baseUrl: String

    init {
        val now = clock.now()
        coordinator = Coordinator(initialState(now), InMemoryCoordinatorStore(), clock, fingerprint = { "test-coordinator-fp" })
        runBlocking { coordinator.start() }
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") { coordinatorRoutes(coordinator) }
        runBlocking { server.startSuspend(wait = false) }
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
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

    override fun close() = server.stop(100, 500)
}
