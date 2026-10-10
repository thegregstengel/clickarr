package net.clickarr.playback.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import net.clickarr.core.model.Airing
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupEntry
import net.clickarr.provider.api.PlaybackState
import net.clickarr.provider.testing.FakeMediaProvider
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchReporterTest {
    private val provider = FakeMediaProvider()
    private val episodes = provider.library.episodes.take(2).map { LineupEntry(it.ref, it.runtime, it.title) }
    private val start = Instant.parse("2026-10-09T19:00:00Z")

    private fun airing(entry: LineupEntry) =
        Airing(ChannelId("10"), entry, start, start + 30.minutes, start + entry.duration, cycle = 0, indexInCycle = 0)

    private class Harness(scope: TestScope, provider: FakeMediaProvider, mode: WatchReporter.Mode, first: Airing) {
        val engine = FakePlayerEngine { scope.testScheduler.currentTime }
        val state = MutableStateFlow(TuneState(airing = first, status = TuneStatus.Playing))
        val reporter = WatchReporter({ provider }, engine, scope.backgroundScope, { mode })

        /** Start playing [from] into the item and begin reporting. */
        fun play(from: Duration) {
            engine.seekTo(from)
            engine.play()
            reporter.start(state)
        }
    }

    private fun TestScope.advance(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    @Test
    fun `an episode played nearly to the end in one sitting is marked watched, once`() = runTest {
        val h = Harness(this, provider, WatchReporter.Mode.WATCHED, airing(episodes[0]))
        h.play(from = Duration.ZERO)
        advance(episodes[0].duration * 0.8)
        provider.playedItems.shouldBeEmpty()
        advance(episodes[0].duration * 0.1)
        provider.playedItems shouldBe listOf(episodes[0].ref)
        advance(5.minutes)
        provider.playedItems.size shouldBe 1
        provider.progressReports.shouldBeEmpty()
    }

    @Test
    fun `tuning in for the last few minutes does not count as watching`() = runTest {
        val h = Harness(this, provider, WatchReporter.Mode.WATCHED, airing(episodes[0]))
        h.play(from = episodes[0].duration - 3.minutes)
        advance(3.minutes)
        h.state.value = TuneState(airing = airing(episodes[1]), status = TuneStatus.Playing)
        advance(1.seconds)
        provider.playedItems.shouldBeEmpty()
    }

    @Test
    fun `a seek does not add watched time`() = runTest {
        val h = Harness(this, provider, WatchReporter.Mode.WATCHED, airing(episodes[0]))
        h.play(from = Duration.ZERO)
        advance(1.minutes)
        h.engine.seekTo(episodes[0].duration - 1.minutes)
        advance(1.minutes)
        provider.playedItems.shouldBeEmpty()
    }

    @Test
    fun `progress mode sends the running position and a stop when the program changes`() = runTest {
        val h = Harness(this, provider, WatchReporter.Mode.PROGRESS, airing(episodes[0]))
        h.play(from = Duration.ZERO)
        advance(30.seconds)
        provider.progressReports.map { it.third }.toSet() shouldBe setOf(PlaybackState.PLAYING)
        provider.progressReports.all { it.first == episodes[0].ref } shouldBe true
        h.state.value = TuneState(airing = airing(episodes[1]), status = TuneStatus.Playing)
        advance(1.seconds)
        provider.progressReports.map { it.first to it.third } shouldContain (episodes[0].ref to PlaybackState.STOPPED)
    }

    @Test
    fun `off means the server hears nothing`() = runTest {
        val h = Harness(this, provider, WatchReporter.Mode.OFF, airing(episodes[0]))
        h.play(from = Duration.ZERO)
        advance(episodes[0].duration)
        h.state.value = TuneState(airing = airing(episodes[1]), status = TuneStatus.Playing)
        advance(1.seconds)
        provider.playedItems.shouldBeEmpty()
        provider.progressReports.shouldBeEmpty()
    }
}
