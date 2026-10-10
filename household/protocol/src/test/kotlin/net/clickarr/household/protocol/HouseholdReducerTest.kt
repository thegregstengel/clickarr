package net.clickarr.household.protocol

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
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
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.Lineups
import org.junit.jupiter.api.Test

class HouseholdReducerTest {
    private val now = Instant.parse("2026-10-09T19:00:00Z")
    private val reducer = HouseholdReducer()
    private val coordinator = DeviceId("living-room")

    private fun lineup(channel: ChannelId, id: String = "l-${channel.value}") = Lineups.create(
        LineupSnapshotId(id), channel,
        listOf(LineupEntry(MediaRef(ProviderId("plex"), NativeItemId("1")), 22.minutes, "The Office")),
        now,
    )

    private fun channel(id: String, number: Int, lineupId: String = "l-$id") = Channel(
        id = ChannelId(id), number = number, name = id, source = ProgrammingSource.Explicit(emptyList()),
        seed = 1, lineup = LineupSnapshotId(lineupId), anchor = now,
    )

    private val empty = HouseholdState(
        revision = 0, schedulerVersion = 1,
        household = Household(HouseholdId("h"), "Home", coordinator, now),
        devices = listOf(HouseholdDevice(coordinator, "Living Room", now)),
        servers = emptyList(), channels = emptyList(), lineups = emptyList(), favorites = emptySet(),
    )

    private fun HouseholdState.apply(c: Command, at: Instant = now): HouseholdState =
        (reducer.apply(this, c, at) as Outcome.Success).value

    @Test
    fun `creating a channel bumps the revision and stores the lineup`() {
        val s = empty.apply(Command.CreateChannel(channel("a", 10), lineup(ChannelId("a"))))
        s.revision shouldBe 1
        s.channels.map { it.number } shouldContainExactly listOf(10)
        s.lineups.map { it.id.value } shouldContainExactly listOf("l-a")
    }

    @Test
    fun `duplicate channel numbers and ids are rejected without changing state`() {
        val s = empty.apply(Command.CreateChannel(channel("a", 10), lineup(ChannelId("a"))))
        val dupNumber = reducer.apply(s, Command.CreateChannel(channel("b", 10), lineup(ChannelId("b"))), now)
        dupNumber.shouldBeInstanceOf<Outcome.Failure>().error.shouldBeInstanceOf<ClickarrError.Invalid>()
        val dupId = reducer.apply(s, Command.CreateChannel(channel("a", 11, "l-a2"), lineup(ChannelId("a"), "l-a2")), now)
        dupId.shouldBeInstanceOf<Outcome.Failure>()
    }

    @Test
    fun `deleting a channel removes it, its favorite flag, and its orphaned lineup`() {
        val s = empty.apply(Command.CreateChannel(channel("a", 10), lineup(ChannelId("a"))))
            .apply(Command.SetFavorite(ChannelId("a"), true))
        s.favorites shouldBe setOf(ChannelId("a"))
        val after = s.apply(Command.DeleteChannel(ChannelId("a")))
        after.channels shouldBe emptyList()
        after.favorites shouldBe emptySet()
        after.lineups shouldBe emptyList()
        after.revision shouldBe 3
    }

    @Test
    fun `a pending lineup is promoted once its cut-over time passes`() {
        val s = empty.apply(Command.CreateChannel(channel("a", 10), lineup(ChannelId("a"))))
        val newLineup = lineup(ChannelId("a"), "l-a-new")
        val cutover = now + 30.minutes
        val updated = s.channels.single().copy(pendingLineup = newLineup.id, pendingAt = cutover)
        val queued = s.apply(Command.UpdateChannel(updated, newLineup))
        queued.channels.single().lineup.value shouldBe "l-a"
        queued.lineups.size shouldBe 2
        val later = reducer.applyCutovers(queued, cutover)
        later.channels.single().lineup.value shouldBe "l-a-new"
        later.channels.single().anchor shouldBe cutover
        later.channels.single().pendingLineup shouldBe null
        later.lineups.map { it.id.value } shouldContainExactly listOf("l-a-new")
    }

    @Test
    fun `the coordinator cannot be removed and unknown devices are not found`() {
        reducer.apply(empty, Command.RemoveDevice(coordinator), now).shouldBeInstanceOf<Outcome.Failure>()
        reducer.apply(empty, Command.RemoveDevice(DeviceId("ghost")), now)
            .shouldBeInstanceOf<Outcome.Failure>().error.shouldBeInstanceOf<ClickarrError.NotFound>()
    }

    @Test
    fun `registering a server replaces an entry with the same identity`() {
        val a = net.clickarr.core.model.ServerLocation(net.clickarr.core.model.ProviderKind.PLEX, "m1", "WOPR", listOf("http://a"))
        val b = a.copy(urls = listOf("http://b"))
        val s = empty.apply(Command.RegisterServer(a)).apply(Command.RegisterServer(b))
        s.servers shouldContainExactly listOf(b)
    }
}
