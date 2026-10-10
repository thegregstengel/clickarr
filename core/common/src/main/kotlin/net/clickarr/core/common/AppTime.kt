package net.clickarr.core.common

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The time zone and clock correction the whole app displays with (Settings, General, Time). The zone
 * defaults to the device's; the correction comes from a network time check. Both are read by the app's
 * [Clock] through the DI module, so schedules and the screen agree.
 */
object AppTime {
    @Volatile
    var zone: TimeZone = TimeZone.currentSystemDefault()

    /** Measured against time.google.com or pool.ntp.org when automatic time is on. */
    @Volatile
    var networkOffsetMs: Long = 0L

    fun local(instant: Instant): LocalDateTime = instant.toLocalDateTime(zone)

    /** "7:05 PM" style, the one format every screen uses. */
    fun timeOfDay(instant: Instant): String {
        val local = local(instant)
        val h24 = local.hour
        val h12 = if (h24 % HALF_DAY == 0) HALF_DAY else h24 % HALF_DAY
        val ampm = if (h24 < HALF_DAY) "AM" else "PM"
        return "%d:%02d %s".format(h12, local.minute, ampm)
    }

    private const val HALF_DAY = 12
}
