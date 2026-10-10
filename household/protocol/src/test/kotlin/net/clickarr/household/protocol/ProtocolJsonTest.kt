package net.clickarr.household.protocol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdId
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.Lineups
import org.junit.jupiter.api.Test

/**
 * Wire-format checks. The golden file pins the JSON shape of a command so a change to it is a
 * conscious review decision (proposal 17.1), and the discriminator test guards the sealed types.
 */
class ProtocolJsonTest {
    private val now = Instant.parse("2026-10-09T19:00:00Z")

    private fun sampleCommand(): Command {
        val id = ChannelId("ch-10")
        val lineup = Lineups.create(
            LineupSnapshotId("lineup-1"), id,
            listOf(LineupEntry(MediaRef(ProviderId("plex-1"), NativeItemId("2011")), 22.minutes, "The Office (US)", "S1E1 Pilot")),
            now,
        )
        val channel = Channel(
            id = id, number = 10, name = "Sitcoms",
            source = ProgrammingSource.Shows(listOf(MediaRef(ProviderId("plex-1"), NativeItemId("201")))),
            slotRounding = 30.minutes, seed = 42L, lineup = lineup.id, anchor = now,
        )
        return Command.CreateChannel(channel, lineup)
    }

    @Test
    fun `commands round-trip through JSON with a type discriminator`() {
        val json = ProtocolJson.encodeToString(Command.serializer(), sampleCommand())
        json shouldContain "\"type\":\"createChannel\""
        ProtocolJson.decodeFromString(Command.serializer(), json) shouldBe sampleCommand()
    }

    @Test
    fun `events and info round-trip`() {
        val info = InfoResponse(
            schedulerVersion = 1, householdId = HouseholdId("h"), householdName = "Home", coordinator = DeviceId("d"), revision = 7, now = now,
        )
        val encoded = ProtocolJson.encodeToString(InfoResponse.serializer(), info)
        ProtocolJson.decodeFromString(InfoResponse.serializer(), encoded) shouldBe info
        val ev: Event = Event.RevisionChanged(8)
        ProtocolJson.decodeFromString(Event.serializer(), ProtocolJson.encodeToString(Event.serializer(), ev)) shouldBe ev
    }

    @Test
    fun `unknown fields are ignored so newer coordinators do not break older members`() {
        val json = """{"type":"revision","revision":9,"futureField":"ignored"}"""
        ProtocolJson.decodeFromString(Event.serializer(), json) shouldBe Event.RevisionChanged(9)
    }

    @Test
    fun `golden command JSON matches the committed file`() {
        val actual = ProtocolJson.encodeToString(Command.serializer(), sampleCommand())
        val resource = javaClass.getResourceAsStream("/golden/create-channel.json")
        if (resource == null) {
            // First run on a new wire shape: print it so it can be reviewed and committed as the golden file.
            println("GOLDEN create-channel.json BEGIN\n$actual\nGOLDEN create-channel.json END")
            return
        }
        actual shouldBe resource.bufferedReader().readText().trim()
    }
}
