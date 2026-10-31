package net.clickarr.feature.player

import androidx.compose.ui.input.key.Key

/**
 * Where player remote focus sits. Prefer moving [FocusRequester]s over swallowing channel keys:
 * the stream panel and gear own OK / D-pad when they are the host; Up/Down still change channel
 * from the park or gear.
 */
internal enum class PlayerFocusHost {
    /** Full-screen video; overlay may be hidden. */
    Park,

    /** Settings gear under the channel badge (Audio & video). */
    Gear,

    /** Audio & video list panel. */
    StreamPanel,

    /** Now / upcoming strip. */
    MiniGuide,
}

/** Resolve which surface owns the remote while a channel plays. */
internal fun resolvePlayerFocusHost(
    streamPanel: Boolean,
    miniGuideOpen: Boolean,
    gearFocused: Boolean,
    overlayVisible: Boolean,
): PlayerFocusHost = when {
    streamPanel -> PlayerFocusHost.StreamPanel
    miniGuideOpen -> PlayerFocusHost.MiniGuide
    gearFocused && overlayVisible -> PlayerFocusHost.Gear
    else -> PlayerFocusHost.Park
}

/** Keys Compose focus on the stream panel must receive; parent preview must not consume them. */
internal fun passesToFocusedChild(host: PlayerFocusHost, key: Key): Boolean {
    if (host != PlayerFocusHost.StreamPanel) return false
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}
