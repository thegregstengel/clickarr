package net.clickarr.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SettingsLockTest {
    @Test
    fun `the stored record matches its code and nothing else`() {
        val record = SettingsLock.record("1234")
        SettingsLock.matches("1234", record) shouldBe true
        SettingsLock.matches("1235", record) shouldBe false
        SettingsLock.matches("", record) shouldBe false
        SettingsLock.matches("1234", "") shouldBe false
        SettingsLock.matches("1234", "garbage") shouldBe false
    }

    @Test
    fun `two records of the same code differ, so the record does not reveal the code`() {
        SettingsLock.record("0000") shouldNotBe SettingsLock.record("0000")
    }

    @Test
    fun `only four digits make a code`() {
        assertThrows<IllegalArgumentException> { SettingsLock.record("123") }
        assertThrows<IllegalArgumentException> { SettingsLock.record("12a4") }
    }

    @Test
    fun `five wrong guesses in a row earn a thirty second wait, a right one clears it`() {
        val a = SettingsLock.Attempts(maxFailures = 5, cooldownMs = 30_000)
        repeat(4) { a.failed(nowMs = 1_000) }
        a.waitMs(nowMs = 1_000) shouldBe 0
        a.failed(nowMs = 1_000)
        a.waitMs(nowMs = 1_000) shouldBe 30_000
        a.waitMs(nowMs = 20_000) shouldBe 11_000
        a.waitMs(nowMs = 31_000) shouldBe 0
        a.failed(nowMs = 40_000)
        a.succeeded()
        a.waitMs(nowMs = 40_000) shouldBe 0
    }
}
