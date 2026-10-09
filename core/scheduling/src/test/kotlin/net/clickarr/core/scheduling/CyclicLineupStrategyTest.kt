package net.clickarr.core.scheduling

import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import org.junit.jupiter.api.Test

class CyclicLineupStrategyTest {
    private val anchor = Instant.parse("2026-10-09T19:00:00Z") // 7:00 PM in the examples
    private val provider = ProviderId("plex-1")

    private fun entries(vararg minutes: Int): List<LineupEntry> =
        minutes.mapIndexed { i, m -> LineupEntry(MediaRef(provider, NativeItemId("item-$i")), m.minutes, "Title $i") }

    private fun lineup(entries: List<LineupEntry>) =
        Lineups.create(LineupSnapshotId("lineup-1"), ChannelId("ch-10"), entries, anchor)

    private fun channel(
        order: OrderingMode = OrderingMode.SEQUENTIAL,
        rounding: Duration? = null,
        seed: Long = 42,
        anchor: Instant = this.anchor,
    ) = Channel(
        id = ChannelId("ch-10"), number = 10, name = "Sitcoms",
        source = ProgrammingSource.Explicit(emptyList()), order = order, slotRounding = rounding,
        seed = seed, lineup = LineupSnapshotId("lineup-1"), anchor = anchor,
    )

    @Test
    fun `the brief's example - tune in at 7-17 lands 17 minutes into The Office`() {
        val sitcoms = listOf("The Office", "Parks and Recreation", "Seinfeld", "Community")
            .mapIndexed { i, t -> LineupEntry(MediaRef(provider, NativeItemId("$i")), 22.minutes, t) }
        val strategy = CyclicLineupStrategy()
        val ch = channel(rounding = 30.minutes)
        val at = anchor + 17.minutes
        val airing = strategy.airingAt(ch, lineup(sitcoms), at).shouldNotBeNull()
        airing.entry.title shouldBe "The Office"
        airing.start shouldBe anchor
        airing.contentEnd shouldBe anchor + 22.minutes
        airing.end shouldBe anchor + 30.minutes
        (at - airing.start) shouldBe 17.minutes
        strategy.airingAt(ch, lineup(sitcoms), anchor + 52.minutes).shouldNotBeNull().entry.title shouldBe "Parks and Recreation"
    }

    @Test
    fun `sequential order follows the lineup and wraps into the next cycle`() {
        val strategy = CyclicLineupStrategy()
        val l = lineup(entries(30, 30, 60))
        val list = strategy.airingsBetween(channel(), l, anchor, anchor + 4.hours)
        list.map { it.entry.title } shouldBe listOf("Title 0", "Title 1", "Title 2", "Title 0", "Title 1", "Title 2")
        list.map { it.cycle } shouldBe listOf(0L, 0L, 0L, 1L, 1L, 1L)
    }

    @Test
    fun `nothing airs before the anchor and nothing airs on a disabled channel`() {
        val strategy = CyclicLineupStrategy()
        strategy.airingAt(channel(), lineup(entries(30)), anchor - 1.seconds).shouldBeNull()
        strategy.airingAt(channel().copy(enabled = false), lineup(entries(30)), anchor).shouldBeNull()
        strategy.airingAt(channel(), lineup(entries(30)), anchor).shouldNotBeNull()
    }

    @Test
    fun `padding rounds each slot up to the grid and filler fills the gap`() {
        CyclicLineupStrategy.paddedMs(22.minutes, 30.minutes) shouldBe 30.minutes.inWholeMilliseconds
        CyclicLineupStrategy.paddedMs(30.minutes, 30.minutes) shouldBe 30.minutes.inWholeMilliseconds
        CyclicLineupStrategy.paddedMs(31.minutes, 30.minutes) shouldBe 60.minutes.inWholeMilliseconds
        CyclicLineupStrategy.paddedMs(22.minutes, null) shouldBe 22.minutes.inWholeMilliseconds
        CyclicLineupStrategy.paddedMs(Duration.ZERO, null) shouldBe CyclicLineupStrategy.MIN_SLOT.inWholeMilliseconds
    }

    @Test
    fun `airings tile time with no gaps or overlaps, for any lineup, seed, rounding, and window`() = runBlocking {
        val strategy = CyclicLineupStrategy()
        checkAll(
            300,
            Arb.list(Arb.int(1..180), 1..40),
            Arb.long(),
            Arb.int(0..3),
            Arb.int(0..(6 * 60)),
        ) { mins, seed, roundingChoice, fromOffsetMin ->
            val rounding = listOf(null, 5.minutes, 15.minutes, 30.minutes)[roundingChoice]
            val order = if (seed % 2 == 0L) OrderingMode.SEQUENTIAL else OrderingMode.SHUFFLE
            val ch = channel(order = order, rounding = rounding, seed = seed)
            val l = lineup(entries(*mins.toIntArray()))
            val from = anchor + fromOffsetMin.minutes
            val to = from + 5.hours
            val list = strategy.airingsBetween(ch, l, from, to)
            list.isNotEmpty() shouldBe true
            (list.first().start <= from) shouldBe true
            (list.last().end >= to) shouldBe true
            list.zipWithNext().forEach { (a, b) -> b.start shouldBe a.end }
            list.forEach { a ->
                (a.contentEnd <= a.end) shouldBe true
                (a.contentEnd > a.start) shouldBe true
                strategy.airingAt(ch, l, a.start).shouldNotBeNull().entry shouldBe a.entry
                strategy.airingAt(ch, l, a.end - 1.seconds).shouldNotBeNull().entry shouldBe a.entry
            }
        }
    }

    @Test
    fun `two independent strategy instances agree everywhere - the household contract`() = runBlocking {
        checkAll(200, Arb.list(Arb.int(1..120), 1..30), Arb.long(), Arb.long(0L..(365L * 24 * 60))) { mins, seed, minutesAhead ->
            val a = CyclicLineupStrategy()
            val b = CyclicLineupStrategy()
            val ch = channel(order = OrderingMode.SHUFFLE, rounding = 30.minutes, seed = seed)
            val l = lineup(entries(*mins.toIntArray()))
            val at = anchor + minutesAhead.minutes
            a.airingAt(ch, l, at) shouldBe b.airingAt(ch, l, at)
        }
    }

    @Test
    fun `shuffle plays every item exactly once per cycle and reorders between cycles`() {
        val strategy = CyclicLineupStrategy()
        val l = lineup(entries(*IntArray(24) { 30 }))
        val ch = channel(order = OrderingMode.SHUFFLE, seed = 7)
        val cycle0 = strategy.airingsBetween(ch, l, anchor, anchor + 12.hours)
        val cycle1 = strategy.airingsBetween(ch, l, anchor + 12.hours, anchor + 24.hours)
        cycle0.size shouldBe 24
        cycle0.map { it.entry.ref }.toSet().size shouldBe 24
        cycle1.map { it.entry.ref }.toSet().size shouldBe 24
        (cycle0.map { it.entry.ref } == cycle1.map { it.entry.ref }) shouldBe false
        cycle0.map { it.indexInCycle }.shouldBeSorted()
    }

    @Test
    fun `next continues across the cycle boundary`() {
        val strategy = CyclicLineupStrategy()
        val l = lineup(entries(30, 30))
        var a = strategy.airingAt(channel(), l, anchor).shouldNotBeNull()
        val seen = mutableListOf(a.entry.title)
        repeat(5) {
            a = strategy.next(channel(), l, a).shouldNotBeNull()
            seen += a.entry.title
        }
        seen shouldBe listOf("Title 0", "Title 1", "Title 0", "Title 1", "Title 0", "Title 1")
        a.cycle shouldBe 2L
    }

    @Test
    fun `content hash ignores titles but not order, ids, or durations`() {
        val base = entries(30, 45)
        Lineups.contentHash(base) shouldBe Lineups.contentHash(base.map { it.copy(title = "renamed") })
        (Lineups.contentHash(base) == Lineups.contentHash(base.reversed())) shouldBe false
        (Lineups.contentHash(base) == Lineups.contentHash(entries(30, 46))) shouldBe false
    }

    @Test
    fun `a decade of elapsed time is still exact`() {
        val strategy = CyclicLineupStrategy()
        val l: LineupSnapshot = lineup(entries(22, 22, 22))
        val ch = channel(order = OrderingMode.SHUFFLE, rounding = 30.minutes, seed = 1)
        val at = anchor + (3650L * 24).hours + 17.minutes
        val airing = strategy.airingAt(ch, l, at).shouldNotBeNull()
        (at - airing.start) shouldBe 17.minutes
        airing.end - airing.start shouldBe 30.minutes
    }
}
