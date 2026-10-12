package net.clickarr.core.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class SeasonWindowTest {
    @Test
    fun `October covers October only`() {
        SeasonWindow.HALLOWEEN.contains(LocalDate(2026, 10, 1)) shouldBe true
        SeasonWindow.HALLOWEEN.contains(LocalDate(2026, 10, 31)) shouldBe true
        SeasonWindow.HALLOWEEN.contains(LocalDate(2026, 11, 1)) shouldBe false
    }

    @Test
    fun `a window across the new year wraps`() {
        val w = SeasonWindow(12, 1, 1, 6)
        w.contains(LocalDate(2026, 12, 25)) shouldBe true
        w.contains(LocalDate(2027, 1, 6)) shouldBe true
        w.contains(LocalDate(2027, 1, 7)) shouldBe false
        w.contains(LocalDate(2026, 11, 30)) shouldBe false
    }

    @Test
    fun `the stored form round-trips, and junk decodes to all year`() {
        SeasonWindow.decode(SeasonWindow.CHRISTMAS.encode()) shouldBe SeasonWindow.CHRISTMAS
        SeasonWindow.decode("garbage") shouldBe null
        SeasonWindow.decode(null) shouldBe null
    }
}
