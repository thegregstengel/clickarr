package net.clickarr.feature.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Holds TV focus on a hidden node across a composition swap. When the focused button is about to vanish
 * (the pad replacing "Set a code", the gate going away on a correct code), focus parks here first instead
 * of falling to the shell's tab row, which would read as "go to the guide". Whoever appears next asks for it.
 */
internal class FocusPark {
    val anchor = FocusRequester()

    fun park() {
        runCatching { anchor.requestFocus() }
    }
}

/** The anchor itself; composed once by the settings screen, outside anything that comes and goes. */
@Composable
internal fun FocusParkAnchor(park: FocusPark) {
    Box(Modifier.size(1.dp).focusRequester(park.anchor).focusable())
}
