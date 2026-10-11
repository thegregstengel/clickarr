package net.clickarr.household.coordinator

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes
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
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.Lineups
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.CommandResponse
import net.clickarr.household.protocol.InfoResponse
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.PairStartResponse
import net.clickarr.household.protocol.Pairing
import net.clickarr.household.protocol.Pairing.toHex
import net.clickarr.household.protocol.Spake2
import net.clickarr.household.protocol.ProtocolJson
import org.junit.jupiter.api.Test

/** Coordinator and member in one JVM through Ktor's test host (proposal 17.1, "Pairing"). */
class CoordinatorRoutesTest {
    private val t0 = Instant.parse("2026-10-09T19:00:00Z")
    private var nowMs = t0.toEpochMilliseconds()
    private val clock = Clock { Instant.fromEpochMilliseconds(nowMs) }
    private val livingRoom = DeviceId("living-room")

    private fun initial() = HouseholdState(
        revision = 0, schedulerVersion = 1,
        household = Household(HouseholdId("h1"), "Home", livingRoom, t0),
        devices = listOf(HouseholdDevice(livingRoom, "Living Room", t0, t0)),
        servers = emptyList(), channels = emptyList(), lineups = emptyList(), favorites = emptySet(),
    )

    private fun coordinator(store: InMemoryCoordinatorStore = InMemoryCoordinatorStore()) =
        Coordinator(initial(), store, clock, fingerprint = { "coord-fp" })

    private inline fun <reified T> HttpResponse.body(): T = ProtocolJson.decodeFromString(
        kotlinx.serialization.serializer<T>(), kotlinx.coroutines.runBlocking { bodyAsText() },
    )

    /** Sends pair/start as the joiner would; returns the joiner's exchange and the coordinator's answer. */
    private suspend fun ApplicationTestBuilder.start(pin: String, deviceId: String = "bedroom", fp: String = "bed-fp"):
        Pair<Spake2, HttpResponse> {
        val pake = Pairing.joiner(pin, fp, "coord-fp")
        val r = client.post("/v1/pair/start") {
            contentType(ContentType.Application.Json)
            val request = PairStartRequest(DeviceId(deviceId), "Bedroom", fp, pake.message.toHex())
            setBody(ProtocolJson.encodeToString(PairStartRequest.serializer(), request))
        }
        return pake to r
    }

    private suspend fun ApplicationTestBuilder.pair(pin: String, deviceId: String = "bedroom"): PairCompleteResponse {
        val (pake, r) = start(pin, deviceId)
        val started = r.body<PairStartResponse>()
        val keys = pake.finish(Pairing.fromHex(started.pake)!!, Pairing.aad(started.sessionId))
        Pairing.verify(keys.confirmB.toHex(), started.confirm) shouldBe true
        val done = client.post("/v1/pair/complete") {
            contentType(ContentType.Application.Json)
            val request = PairCompleteRequest(started.sessionId, keys.confirmA.toHex())
            setBody(ProtocolJson.encodeToString(PairCompleteRequest.serializer(), request))
        }
        done.status shouldBe HttpStatusCode.OK
        return done.body()
    }

    @Test
    fun `info is public and reports the coordinator clock`() = testApplication {
        val c = coordinator()
        application { coordinatorRoutes(c) }
        val info = client.get("/v1/info").body<InfoResponse>()
        info.householdName shouldBe "Home"
        info.revision shouldBe 0
        info.now shouldBe t0
        info.acceptingJoins shouldBe false
    }

    @Test
    fun `state and commands require a paired token`() = testApplication {
        val c = coordinator()
        application { coordinatorRoutes(c) }
        client.get("/v1/state").status shouldBe HttpStatusCode.Unauthorized
        client.get("/v1/state") { header("Authorization", "Bearer nope") }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `pairing with the displayed PIN issues a token that unlocks state and commands`() = testApplication {
        val store = InMemoryCoordinatorStore()
        val c = coordinator(store)
        application { coordinatorRoutes(c) }
        val pin = c.beginAcceptingJoins().pin
        val paired = pair(pin)
        paired.coordinatorFingerprint shouldBe "coord-fp"
        paired.state.devices.map { it.name } shouldBe listOf("Living Room", "Bedroom")
        store.tokens.size shouldBe 1

        val state = client.get("/v1/state") { header("Authorization", "Bearer ${paired.deviceToken}") }
        state.status shouldBe HttpStatusCode.OK
        state.headers["ETag"] shouldBe "\"1\""
        client.get("/v1/state") {
            header("Authorization", "Bearer ${paired.deviceToken}")
            header("If-None-Match", "\"1\"")
        }.status shouldBe HttpStatusCode.NotModified

        val channelId = ChannelId("ch-10")
        val lineup = Lineups.create(
            LineupSnapshotId("l1"), channelId,
            listOf(LineupEntry(MediaRef(ProviderId("plex"), NativeItemId("1")), 22.minutes, "The Office")), t0,
        )
        val channel = Channel(
            id = channelId, number = 10, name = "Sitcoms", source = ProgrammingSource.Explicit(emptyList()),
            seed = 1, lineup = lineup.id, anchor = t0,
        )
        val created = client.post("/v1/commands") {
            header("Authorization", "Bearer ${paired.deviceToken}")
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(Command.serializer(), Command.CreateChannel(channel, lineup)))
        }
        created.status shouldBe HttpStatusCode.OK
        created.body<CommandResponse>().revision shouldBe 2
        c.state.value.channels.single().number shouldBe 10
        client.get("/v1/info").body<InfoResponse>().acceptingJoins shouldBe false // one PIN pairs one device
    }

    @Test
    fun `a wrong PIN shows up at the joiner, and the PIN is cancelled after three attempts`() = testApplication {
        val c = coordinator()
        application { coordinatorRoutes(c) }
        c.beginAcceptingJoins()
        repeat(3) {
            val (pake, r) = start("000000", "x", "x-fp")
            r.status shouldBe HttpStatusCode.OK
            val started = r.body<PairStartResponse>()
            val keys = pake.finish(Pairing.fromHex(started.pake)!!, Pairing.aad(started.sessionId))
            Pairing.verify(keys.confirmB.toHex(), started.confirm) shouldBe false
        }
        val (_, fourth) = start("000000", "x", "x-fp")
        fourth.status shouldBe HttpStatusCode.Unauthorized
        fourth.bodyAsText() shouldContain "Too many"
        client.get("/v1/info").body<InfoResponse>().acceptingJoins shouldBe false
    }

    @Test
    fun `a forged confirmation at completion cancels the PIN at once`() = testApplication {
        val c = coordinator()
        application { coordinatorRoutes(c) }
        val pin = c.beginAcceptingJoins().pin
        val (_, r) = start(pin, "x", "x-fp")
        val started = r.body<PairStartResponse>()
        val done = client.post("/v1/pair/complete") {
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(PairCompleteRequest.serializer(), PairCompleteRequest(started.sessionId, "00".repeat(32))))
        }
        done.status shouldBe HttpStatusCode.Unauthorized
        client.get("/v1/info").body<InfoResponse>().acceptingJoins shouldBe false
    }

    @Test
    fun `pairing is refused when the coordinator is not accepting joins or the PIN expired`() = testApplication {
        val c = coordinator()
        application { coordinatorRoutes(c) }
        val closed = client.post("/v1/pair/start") {
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(PairStartRequest.serializer(), PairStartRequest(DeviceId("x"), "X", "x-fp", "04")))
        }
        closed.status shouldBe HttpStatusCode.Unauthorized

        c.beginAcceptingJoins()
        nowMs += 3.minutes.inWholeMilliseconds
        client.get("/v1/info").body<InfoResponse>().acceptingJoins shouldBe false
    }

    @Test
    fun `removing a device revokes its token`() = testApplication {
        val store = InMemoryCoordinatorStore()
        val c = coordinator(store)
        application { coordinatorRoutes(c) }
        val token = pair(c.beginAcceptingJoins().pin).deviceToken
        client.get("/v1/state") { header("Authorization", "Bearer $token") }.status shouldBe HttpStatusCode.OK
        c.apply(Command.RemoveDevice(DeviceId("bedroom")), livingRoom)
        client.get("/v1/state") { header("Authorization", "Bearer $token") }.status shouldBe HttpStatusCode.Unauthorized
        store.tokens.size shouldBe 0
    }
}
