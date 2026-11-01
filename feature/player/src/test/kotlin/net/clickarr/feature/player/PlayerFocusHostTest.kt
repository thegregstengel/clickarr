package net.clickarr.feature.player

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlayerFocusHostTest {
    @Test
    fun `resolve prefers stream panel then mini-guide then gear`() {
        resolvePlayerFocusHost(streamPanel = true, miniGuideOpen = true, gearFocused = true, overlayVisible = true) shouldBe
            PlayerFocusHost.StreamPanel
        resolvePlayerFocusHost(streamPanel = false, miniGuideOpen = true, gearFocused = true, overlayVisible = true) shouldBe
            PlayerFocusHost.MiniGuide
        resolvePlayerFocusHost(streamPanel = false, miniGuideOpen = false, gearFocused = true, overlayVisible = true) shouldBe
            PlayerFocusHost.Gear
        resolvePlayerFocusHost(streamPanel = false, miniGuideOpen = false, gearFocused = true, overlayVisible = false) shouldBe
            PlayerFocusHost.Park
    }

    @Test
    fun `stream panel passes D-pad and OK to Compose focus`() {
        val keys = listOf(
            Key.DirectionUp,
            Key.DirectionDown,
            Key.DirectionLeft,
            Key.DirectionRight,
            Key.DirectionCenter,
            Key.Enter,
        )
        keys.forEach { key ->
            passesToFocusedChild(PlayerFocusHost.StreamPanel, key) shouldBe true
        }
    }

    @Test
    fun `park gear and mini-guide do not pass D-pad through preview`() {
        for (host in listOf(PlayerFocusHost.Park, PlayerFocusHost.Gear, PlayerFocusHost.MiniGuide)) {
            passesToFocusedChild(host, Key.DirectionUp) shouldBe false
            passesToFocusedChild(host, Key.DirectionLeft) shouldBe false
            passesToFocusedChild(host, Key.Enter) shouldBe false
        }
    }

    @Test
    fun `stream panel still lets channel keys be handled by the parent`() {
        passesToFocusedChild(PlayerFocusHost.StreamPanel, Key.ChannelUp) shouldBe false
        passesToFocusedChild(PlayerFocusHost.StreamPanel, Key.Menu) shouldBe false
    }
}
