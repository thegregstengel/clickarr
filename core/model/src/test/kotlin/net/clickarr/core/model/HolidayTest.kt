package net.clickarr.core.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class HolidayTest {
    @Test
    fun `Easter and Thanksgiving land on the right days`() {
        Holiday.easter(2026) shouldBe LocalDate(2026, 4, 5)
        Holiday.easter(2027) shouldBe LocalDate(2027, 3, 28)
        Holiday.easter(2024) shouldBe LocalDate(2024, 3, 31)
        Holiday.thanksgiving(2026) shouldBe LocalDate(2026, 11, 26)
        Holiday.thanksgiving(2027) shouldBe LocalDate(2027, 11, 25)
        Holiday.thanksgiving(2024) shouldBe LocalDate(2024, 11, 28)
    }

    @Test
    fun `windows end on the holiday and do not overlap`() {
        Holiday.EASTER.window(2026) shouldBe LocalDate(2026, 3, 29)..LocalDate(2026, 4, 5)
        Holiday.VALENTINES.window(2026) shouldBe LocalDate(2026, 2, 7)..LocalDate(2026, 2, 14)
        Holiday.HALLOWEEN.contains(LocalDate(2026, 10, 31)) shouldBe true
        Holiday.HALLOWEEN.contains(LocalDate(2026, 11, 1)) shouldBe false
        Holiday.THANKSGIVING.contains(LocalDate(2026, 11, 1)) shouldBe true
        Holiday.THANKSGIVING.contains(LocalDate(2026, 11, 27)) shouldBe false
        Holiday.CHRISTMAS.contains(LocalDate(2026, 12, 25)) shouldBe true
        Holiday.CHRISTMAS.contains(LocalDate(2026, 12, 26)) shouldBe false
    }
}
