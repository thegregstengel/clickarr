package net.clickarr.feature.player

/**
 * Tells a tap of OK from a hold, from the key stream alone: [down] for each key-down (the first, then the
 * remote's repeats while held) and [up] on release. A hold fires once, as soon as it passes [holdMs]; a tap fires
 * on release. A release with no press seen here (OK pressed on another screen) does nothing.
 */
class OkKeyGesture(private val onTap: () -> Unit, private val onHold: () -> Unit, private val holdMs: Long = HOLD_MS) {
    private var downAt = -1L
    private var held = false

    fun down(atMs: Long, repeat: Boolean) {
        if (!repeat) {
            downAt = atMs
            held = false
            return
        }
        if (downAt >= 0 && !held && atMs - downAt >= holdMs) {
            held = true
            onHold()
        }
    }

    fun up() {
        if (downAt >= 0 && !held) onTap()
        downAt = -1L
        held = false
    }

    private companion object {
        const val HOLD_MS = 600L
    }
}
