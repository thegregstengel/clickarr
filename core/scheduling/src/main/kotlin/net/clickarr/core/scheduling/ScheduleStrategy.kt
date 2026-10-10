package net.clickarr.core.scheduling

import kotlinx.datetime.Instant
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.LineupSnapshot

/**
 * A channel is a pure function from time to program. Strategies implement that function for one
 * style of programming. The MVP ships CyclicLineupStrategy (Phase 1); time blocks, overrides, and
 * interstitials arrive later as additional strategies without changing callers (ADR 0009).
 */
interface ScheduleStrategy {
    fun airingAt(channel: Channel, lineup: LineupSnapshot, at: Instant): Airing?

    fun airingsBetween(channel: Channel, lineup: LineupSnapshot, from: Instant, to: Instant): List<Airing>

    fun next(channel: Channel, lineup: LineupSnapshot, after: Airing): Airing?
}

/**
 * Bumped only when a change would make an older build compute a different schedule from the same
 * inputs. Household members refuse state stamped with a newer version (proposal 9.1).
 */
const val SCHEDULER_VERSION: Int = 2
