package net.clickarr.core.common

import kotlinx.datetime.Instant

/**
 * Source of "now". Everything that schedules or tunes takes a Clock so tests can freeze time
 * and so a household member can apply the coordinator-relative offset (ADR 0008, proposal 9.5).
 */
fun interface Clock {
    fun now(): Instant

    companion object {
        val System: Clock = Clock { kotlinx.datetime.Clock.System.now() }
    }
}

/** A clock shifted by a fixed offset, used for coordinator clock alignment. */
class OffsetClock(private val base: Clock, private val offsetMillis: () -> Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(base.now().toEpochMilliseconds() + offsetMillis())
}
