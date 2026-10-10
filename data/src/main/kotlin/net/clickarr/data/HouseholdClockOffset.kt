package net.clickarr.data

/**
 * Offset between the coordinator's clock and this device's, learned from every /v1/info and /v1/state
 * response (proposal 9.5). Zero when this device is the coordinator or not in a household.
 */
object HouseholdClockOffset {
    @Volatile
    var offsetMs: Long = 0L
        private set

    /** Smooth so a single slow request does not jerk the schedule. */
    fun observe(coordinatorNowMs: Long, localNowMs: Long) {
        val sample = coordinatorNowMs - localNowMs
        offsetMs = if (offsetMs == 0L) sample else (offsetMs * 3 + sample) / 4
    }

    fun reset() {
        offsetMs = 0L
    }
}
