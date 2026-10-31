package net.clickarr.feature.player

import androidx.compose.ui.focus.FocusRequester

/** Focus wiring for the overlay Audio & video gear (packs ChannelOverlay parameters for Detekt). */
class StreamGearBinding(
    val focus: FocusRequester,
    val onFocused: (Boolean) -> Unit,
)
