package net.clickarr.feature.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OkKeyGestureTest {
    private var taps = 0
    private var holds = 0
    private val ok = OkKeyGesture(onTap = { taps++ }, onHold = { holds++ }, holdMs = 600)

    @Test
    fun `a quick press is a tap`() {
        ok.down(1_000, repeat = false)
        ok.up()
        taps shouldBe 1
        holds shouldBe 0
    }

    @Test
    fun `holding past the threshold fires the hold once and no tap`() {
        ok.down(1_000, repeat = false)
        ok.down(1_400, repeat = true)
        ok.down(1_650, repeat = true)
        ok.down(1_700, repeat = true)
        ok.up()
        holds shouldBe 1
        taps shouldBe 0
    }

    @Test
    fun `a release with no press seen here does nothing`() {
        ok.up()
        taps shouldBe 0
    }
}
