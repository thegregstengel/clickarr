package net.clickarr.core.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class ChannelNumberTest {
    private fun channel(id: String, number: Int, holiday: Holiday? = null) = Channel(
        id = ChannelId(id), number = number, name = id, source = ProgrammingSource.Explicit(emptyList()), seed = 1,
        lineup = LineupSnapshotId("l-$id"), anchor = Instant.fromEpochMilliseconds(0), holiday = holiday,
    )

    @Test
    fun `holiday channels for different holidays share a number`() {
        channel("xmas", 100, Holiday.CHRISTMAS).clashesWith(channel("spooky", 100, Holiday.HALLOWEEN)) shouldBe false
    }

    @Test
    fun `a regular channel never shares, and nor do two channels for the same holiday`() {
        channel("xmas", 100, Holiday.CHRISTMAS).clashesWith(channel("news", 100)) shouldBe true
        channel("news", 100).clashesWith(channel("xmas", 100, Holiday.CHRISTMAS)) shouldBe true
        channel("a", 100, Holiday.EASTER).clashesWith(channel("b", 100, Holiday.EASTER)) shouldBe true
    }

    @Test
    fun `a channel does not clash with itself or another number`() {
        val c = channel("a", 7)
        c.clashesWith(c) shouldBe false
        c.clashesWith(channel("b", 8)) shouldBe false
    }
}
