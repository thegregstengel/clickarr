package net.clickarr.core.scheduling

import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.OrderingMode

/**
 * Orders a lineup in runs: each group's entries (a show's episodes, in lineup order) are cut into runs of
 * [EpisodeRuns.min] to [EpisodeRuns.max], then the runs are shuffled, or dealt round-robin across groups
 * when the channel plays in order. Entries without a group (movies) are runs of one. Deterministic:
 * every choice comes from the cycle's [DeterministicRandom], so every device deals the same hand.
 */
internal object EpisodeRunOrder {
    fun permutation(entries: List<LineupEntry>, order: OrderingMode, runs: EpisodeRuns, random: DeterministicRandom): IntArray {
        val streams = LinkedHashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, e -> streams.getOrPut(e.group ?: "#$i") { ArrayList() }.add(i) }
        val perStream = streams.values.map { indices -> chop(indices, runs, random) }
        val dealt: List<List<Int>> = when (order) {
            OrderingMode.SEQUENTIAL -> roundRobin(perStream)
            OrderingMode.SHUFFLE -> {
                val all = perStream.flatten()
                val p = random.permutation(all.size)
                List(all.size) { all[p[it]] }
            }
        }
        return dealt.flatten().toIntArray()
    }

    private fun chop(indices: List<Int>, runs: EpisodeRuns, random: DeterministicRandom): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var at = 0
        while (at < indices.size) {
            val size = runs.min + random.nextInt(runs.max - runs.min + 1)
            out += indices.subList(at, minOf(at + size, indices.size))
            at += size
        }
        return out
    }

    /** One run from each stream in turn until all are spent, so two shows alternate instead of one playing out. */
    private fun roundRobin(perStream: List<List<List<Int>>>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        val longest = perStream.maxOfOrNull { it.size } ?: 0
        for (round in 0 until longest) perStream.forEach { stream -> stream.getOrNull(round)?.let { out += it } }
        return out
    }
}
