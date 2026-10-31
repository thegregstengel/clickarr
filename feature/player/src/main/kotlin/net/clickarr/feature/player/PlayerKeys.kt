package net.clickarr.feature.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Key-down events, except OK, which is a short press (the overlay) or a hold (captions) and so is decided on
 * key-up. The remote's own captions key, where there is one, switches captions too. The stream panel and
 * mini-guide take the keys they own while they are up.
 */
internal fun playerKey(
    event: KeyEvent,
    vm: PlayerViewModel,
    host: PlayerFocusHost,
    onOpenGuide: () -> Unit,
    onFocusGear: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val down = event.type == KeyEventType.KeyDown
    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter
    return when {
        // Any button answers "Are you still watching?" or wakes the player, and goes no further.
        down && native.repeatCount == 0 && vm.sleep.onInput() -> true
        // Audio & video list owns OK and D-pad via Compose focus; do not steal them for channel change.
        passesToFocusedChild(host, event.key) -> false
        host != PlayerFocusHost.StreamPanel && ok -> okKey(event, vm, onGear = host == PlayerFocusHost.Gear)
        !down -> false
        native.keyCode == android.view.KeyEvent.KEYCODE_CAPTIONS -> true.also { vm.toggleCaptions() }
        host == PlayerFocusHost.MiniGuide -> handleMiniGuideKey(event.key, vm, onFocusGear)
        else -> handleKey(event.key, vm, onOpenGuide, onFocusGear)
    }
}

/** OK is a tap (overlay or gear) or a hold (captions), so both its down and up events go to the gesture. */
private fun okKey(event: KeyEvent, vm: PlayerViewModel, onGear: Boolean): Boolean {
    val native = event.nativeKeyEvent
    val gesture = if (onGear) vm.gearOk else vm.ok
    when (event.type) {
        KeyEventType.KeyDown -> gesture.down(native.eventTime, repeat = native.repeatCount > 0)
        KeyEventType.KeyUp -> gesture.up()
        else -> Unit
    }
    return true
}

/** Remote mapping from design language 2.8, with Left toward the stream gear. Returns true when consumed. */
private fun handleKey(key: Key, vm: PlayerViewModel, onOpenGuide: () -> Unit, onFocusGear: () -> Unit): Boolean {
    val action: (() -> Unit)? = when (key) {
        Key.DirectionUp, Key.ChannelUp -> vm::channelUp
        Key.DirectionDown, Key.ChannelDown -> vm::channelDown
        Key.Menu -> onOpenGuide
        Key.DirectionLeft -> onFocusGear
        Key.DirectionRight -> vm::openMiniGuide
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> vm::playPause
        in DIGIT_KEYS -> ({ vm.enterDigit(DIGIT_KEYS.indexOf(key)) })
        else -> null
    }
    action?.invoke()
    return action != null
}

/**
 * Inside the mini-guide: Right moves ahead; Left moves back or, on the first card, returns to the gear.
 * OK closes (Back closes through the BackHandler).
 */
private fun handleMiniGuideKey(key: Key, vm: PlayerViewModel, onFocusGear: () -> Unit): Boolean {
    when (key) {
        Key.DirectionLeft -> {
            val guide = vm.miniGuide.value
            if (guide != null && guide.index > 0) vm.moveMiniGuide(-1) else onFocusGear()
        }
        Key.DirectionRight -> vm.moveMiniGuide(+1)
        Key.DirectionCenter, Key.Enter -> vm.closeMiniGuide()
        else -> return false
    }
    return true
}

private val DIGIT_KEYS = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)
