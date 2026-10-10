package net.clickarr.household.client

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Outcome
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
import net.clickarr.core.scheduling.CyclicLineupStrategy
import net.clickarr.core.scheduling.Lineups
import net.clickarr.household.coordinator.Coordinator
import net.clickarr.household.coordinator.InMemoryCoordinatorStore
import net.clickarr.household.coordinator.coordinatorRoutes
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.Event
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coordinator on a real socket (CIO), member through the OkHttp client. This is proposal milestone 2
 * expressed as a JVM test: pair, sync, change a channel, get the event, and agree on what is airing.
 */
class HouseholdEndToEndTest {
    private val t0 = Instant.parse("2026-10-09T19:00:00Z")
    private val clock = Clock { t0 }
    private val livingRoom = DeviceId("living-room")
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var coordinator: Coordinator
    private lateinit var baseUrl: String
    private val http = OkHttpClient()

    private fun initial() = HouseholdState(
        revision = 0, schedulerVersion = 1,
        household = Household(HouseholdId("h1"), "Home", livingRoom, t0),
        devices = listOf(HouseholdDevice(livingRoom, "Living Room", t0, t0)),
        servers = emptyList(), channels = emptyList(), lineups = emptyList(), favorites = emptySet(),
    )

    @BeforeEach
    fun startCoordinator() = runBlocking {
        coordinator = Coordinator(initial(), InMemoryCoordinatorStore(), clock, fingerprint = { "coord-fp" })
        server = embeddedServer(CIO, port = 0) { coordinatorRoutes(coordinator) }.also { it.start(wait = false) }
        val port = server.engine.resolvedConnectors().first().port
        baseUrl = "http://127.0.0.1:$port"
    }

    @AfterEach
    fun stop() {
        server.stop(100, 500)
    }

    @Test
    fun `bedroom pairs, syncs, edits through the coordinator, and both TVs agree on the airing`() = runBlocking {
        var token: String? = null
        val bedroom = HouseholdClient(http, baseUrl) { token }

        bedroom.info().shouldBeInstanceOf<Outcome.Success<*>>()
        bedroom.state(null).shouldBeInstanceOf<Outcome.Failure>().error.shouldBeInstanceOf<ClickarrError.Unauthorized>()

        val pin = coordinator.beginAcceptingJoins().pin
        val wrong = HouseholdJoin.join(bedroom, DeviceId("bedroom"), "Bedroom", "bed-fp", "coord-fp", "000000")
        wrong.shouldBeInstanceOf<Outcome.Failure>()
        val joined = HouseholdJoin.join(bedroom, DeviceId("bedroom"), "Bedroom", "bed-fp", "coord-fp", pin)
        val paired = joined.shouldBeInstanceOf<Outcome.Success<*>>().value as net.clickarr.household.protocol.PairCompleteResponse
        token = paired.deviceToken
        paired.state.devices.map { it.name } shouldBe listOf("Living Room", "Bedroom")

        // Initial sync and the 304 path.
        val first = bedroom.state(null).shouldBeInstanceOf<Outcome.Success<HouseholdClient.StateFetch>>().value
        val synced = first.shouldBeInstanceOf<HouseholdClient.StateFetch.Fresh>().state
        bedroom.state(synced.revision).shouldBeInstanceOf<Outcome.Success<HouseholdClient.StateFetch>>()
            .value shouldBe HouseholdClient.StateFetch.NotModified

        // Bedroom creates channel 10 through the coordinator while listening for the event.
        val channelId = ChannelId("ch-10")
        val lineup = Lineups.create(
            LineupSnapshotId("l1"), channelId,
            listOf(
                LineupEntry(MediaRef(ProviderId("plex"), NativeItemId("1")), 22.minutes, "The Office", "S1E1"),
                LineupEntry(MediaRef(ProviderId("plex"), NativeItemId("2")), 22.minutes, "The Office", "S1E2"),
            ),
            t0,
        )
        val channel = Channel(
            id = channelId, number = 10, name = "Sitcoms", source = ProgrammingSource.Explicit(emptyList()),
            order = OrderingMode.SEQUENTIAL, slotRounding = 30.minutes, seed = 7, lineup = lineup.id, anchor = t0,
        )
        val event = coroutineScope {
            val listener = async { withTimeout(10.seconds) { bedroom.events().first { it is Event.RevisionChanged } } }
            Thread.sleep(300) // let the socket connect before the write
            val sent = bedroom.send(Command.CreateChannel(channel, lineup)).shouldBeInstanceOf<Outcome.Success<Long>>()
            sent.value shouldBe synced.revision + 1
            listener.await()
        }
        event shouldBe Event.RevisionChanged(synced.revision + 1)

        // Both devices hold the same state and compute the same airing at 7:17 PM.
        val bedroomState = (bedroom.state(null) as Outcome.Success).value as HouseholdClient.StateFetch.Fresh
        val livingRoomState = coordinator.state.value
        bedroomState.state shouldBe livingRoomState
        val strategy = CyclicLineupStrategy()
        val at = t0 + 17.minutes
        val a = strategy.airingAt(bedroomState.state.channels.single(), bedroomState.state.lineups.single(), at)
        val b = strategy.airingAt(livingRoomState.channels.single(), livingRoomState.lineups.single(), at)
        a shouldBe b
        a!!.entry.subtitle shouldBe "S1E1"
        (at - a.start) shouldBe 17.minutes
    }
}
