package net.clickarr.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LineupRefresherTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun `due when never run, after a day, and not before`() {
        LineupRefresher.isDue(last = null, now = 0) shouldBe true
        LineupRefresher.isDue(last = 0, now = 23 * hour) shouldBe false
        LineupRefresher.isDue(last = 0, now = 24 * hour) shouldBe true
    }

    @Test
    fun `a clock set backwards does not postpone the refresh for years`() {
        LineupRefresher.isDue(last = 400 * 24 * hour, now = 10 * hour) shouldBe true
    }

    @Test
    fun `the summary reads like a sentence fragment`() {
        LineupRefresher.summary(changed = 0, total = 7) shouldBe "no changes in 7 channels"
        LineupRefresher.summary(changed = 1, total = 7) shouldBe "1 of 7 channels picked up changes"
        LineupRefresher.summary(changed = 2, total = 2) shouldBe "2 of 2 channels picked up changes"
        LineupRefresher.summary(changed = 0, total = 1) shouldBe "no changes in 1 channel"
    }
}
