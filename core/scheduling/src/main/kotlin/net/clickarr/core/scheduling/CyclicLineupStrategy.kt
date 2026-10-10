package net.clickarr.core.scheduling

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.OrderingMode

/**
 * The MVP schedule: a channel cycles through its lineup forever, starting at [Channel.anchor].
 *
 * Given N entries with durations d[i], optional slot rounding r, anchor A, seed s:
 *  1. slot[i] = r == null ? d[i] : ceil(d[i] / r) * r
 *  2. cycleLength = sum(slot)
 *  3. elapsed = at - A ; cycle = floor(elapsed / cycleLength) ; offset = elapsed mod cycleLength
 *  4. perm = SEQUENTIAL ? identity : shuffle(seed, cycle)
 *  5. binary-search the prefix sums of slot[perm[i]] for offset
 *
 * Every device holding the same channel and snapshot computes the same [Airing] for the same instant.
 * See ADR 0008 and ADR 0009, proposal section 8.2.
 */
class CyclicLineupStrategy : ScheduleStrategy {

    /** Per (lineup, cycle) permutation and prefix sums. Bounded so a long-running TV cannot grow it forever. */
    private val layouts = LinkedHashMap<LayoutKey, CycleLayout>()

    override fun airingAt(channel: Channel, lineup: LineupSnapshot, at: Instant): Airing? {
        if (lineup.entries.isEmpty() || !channel.enabled) return null
        val elapsed = at - channel.anchor
        if (elapsed.isNegative()) return null
        val geometry = geometry(channel, lineup)
        val elapsedMs = elapsed.inWholeMilliseconds
        val cycle = elapsedMs / geometry.cycleLengthMs
        val offsetMs = elapsedMs % geometry.cycleLengthMs
        val layout = layout(channel, lineup, geometry, cycle)
        val index = layout.indexAt(offsetMs)
        return layout.airing(channel, lineup, geometry, cycle, index)
    }

    override fun airingsBetween(channel: Channel, lineup: LineupSnapshot, from: Instant, to: Instant): List<Airing> {
        if (lineup.entries.isEmpty() || !channel.enabled || to <= from) return emptyList()
        val start = maxOf(from, channel.anchor)
        var current = airingAt(channel, lineup, start) ?: return emptyList()
        val out = ArrayList<Airing>()
        while (current.start < to) {
            out += current
            current = next(channel, lineup, current) ?: break
        }
        return out
    }

    override fun next(channel: Channel, lineup: LineupSnapshot, after: Airing): Airing? {
        if (lineup.entries.isEmpty() || !channel.enabled) return null
        val geometry = geometry(channel, lineup)
        val n = lineup.entries.size
        val (cycle, index) = if (after.indexInCycle + 1 < n) after.cycle to after.indexInCycle + 1 else after.cycle + 1 to 0
        val layout = layout(channel, lineup, geometry, cycle)
        return layout.airing(channel, lineup, geometry, cycle, index)
    }

    private fun geometry(channel: Channel, lineup: LineupSnapshot): Geometry {
        val slots = LongArray(lineup.entries.size) { i -> paddedMs(lineup.entries[i].duration, channel.slotRounding) }
        return Geometry(slots, slots.sum())
    }

    private fun layout(channel: Channel, lineup: LineupSnapshot, geometry: Geometry, cycle: Long): CycleLayout {
        val key = LayoutKey(lineup.id.value, channel.seed, channel.order, channel.slotRounding, channel.runs, cycle)
        layouts[key]?.let { return it }
        val n = lineup.entries.size
        val runs = channel.runs
        val random = DeterministicRandom.forCycle(channel.seed, cycle)
        val perm = when {
            runs != null -> EpisodeRunOrder.permutation(lineup.entries, channel.order, runs, random)
            channel.order == OrderingMode.SEQUENTIAL -> IntArray(n) { it }
            else -> random.permutation(n)
        }
        val prefix = LongArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + geometry.slotsMs[perm[i]]
        val layout = CycleLayout(perm, prefix)
        if (layouts.size >= MAX_CACHED_LAYOUTS) layouts.remove(layouts.keys.first())
        layouts[key] = layout
        return layout
    }

    private class Geometry(val slotsMs: LongArray, val cycleLengthMs: Long)

    private data class LayoutKey(
        val lineupId: String,
        val seed: Long,
        val order: OrderingMode,
        val rounding: Duration?,
        val runs: EpisodeRuns?,
        val cycle: Long,
    )

    private class CycleLayout(val perm: IntArray, val prefixMs: LongArray) {
        /** Largest i with prefix[i] <= offset, i.e. the slot containing offset. */
        fun indexAt(offsetMs: Long): Int {
            var lo = 0
            var hi = perm.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (prefixMs[mid] <= offsetMs) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun airing(channel: Channel, lineup: LineupSnapshot, geometry: Geometry, cycle: Long, index: Int): Airing {
            val entry = lineup.entries[perm[index]]
            val start = channel.anchor + (cycle * geometry.cycleLengthMs + prefixMs[index]).milliseconds
            val slotMs = geometry.slotsMs[perm[index]]
            val contentMs = minOf(entry.duration.inWholeMilliseconds.coerceAtLeast(MIN_SLOT_MS), slotMs)
            return Airing(
                channelId = channel.id,
                entry = entry,
                start = start,
                end = start + slotMs.milliseconds,
                contentEnd = start + contentMs.milliseconds,
                cycle = cycle,
                indexInCycle = index,
            )
        }
    }

    companion object {
        private const val MAX_CACHED_LAYOUTS = 256

        /** A zero or negative duration would make a cycle of length zero; clamp so the math stays finite. */
        internal val MIN_SLOT: Duration = 1.seconds
        private val MIN_SLOT_MS = MIN_SLOT.inWholeMilliseconds

        internal fun paddedMs(duration: Duration, rounding: Duration?): Long {
            val d = duration.inWholeMilliseconds.coerceAtLeast(MIN_SLOT_MS)
            val r = rounding?.inWholeMilliseconds ?: return d
            if (r <= 0) return d
            return ceil(d.toDouble() / r).toLong() * r
        }
    }
}
