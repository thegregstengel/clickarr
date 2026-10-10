package net.clickarr.core.scheduling

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProviderId
import org.junit.jupiter.api.Test

class EpisodeRunOrderTest {
    private val provider = ProviderId("plex-1")

    /** Six episodes of show A then four of B, as a lineup built from two shows arrives. */
    private val entries = (1..6).map { episode("A", it) } + (1..4).map { episode("B", it) }

    private fun episode(show: String, n: Int) =
        LineupEntry(MediaRef(provider, NativeItemId("$show$n")), 22.minutes, "Show $show", "S1E$n", group = show)

    private fun runsOf(perm: IntArray): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>()
        perm.forEach { i ->
            val last = out.lastOrNull()
            if (last != null && entries[last.last()].group == entries[i].group) last += i else out += mutableListOf(i)
        }
        return out
    }

    @Test
    fun `shuffled runs keep aired order inside each run and use every episode once`() {
        val perm = EpisodeRunOrder.permutation(entries, OrderingMode.SHUFFLE, EpisodeRuns(2, 3), DeterministicRandom.forCycle(7, 0))
        perm.toList() shouldContainExactlyInAnyOrder entries.indices.toList()
        runsOf(perm).forEach { run ->
            run shouldBe run.sorted()
            run.size shouldBeLessThanOrEqual 3
        }
    }

    @Test
    fun `runs are at least the minimum except for what is left of a show`() {
        val perm = EpisodeRunOrder.permutation(entries, OrderingMode.SEQUENTIAL, EpisodeRuns(2, 2), DeterministicRandom.forCycle(7, 0))
        // A: 6 episodes in runs of 2; B: 4 episodes in runs of 2; dealt A B A B A.
        runsOf(perm).map { entries[it.first()].group } shouldBe listOf("A", "B", "A", "B", "A")
        runsOf(perm).forEach { it.size shouldBeGreaterThanOrEqual 2 }
    }

    @Test
    fun `the same seed and cycle deal the same hand on every device`() {
        val a = EpisodeRunOrder.permutation(entries, OrderingMode.SHUFFLE, EpisodeRuns(2, 4), DeterministicRandom.forCycle(99, 3))
        val b = EpisodeRunOrder.permutation(entries, OrderingMode.SHUFFLE, EpisodeRuns(2, 4), DeterministicRandom.forCycle(99, 3))
        a.toList() shouldBe b.toList()
    }
}
