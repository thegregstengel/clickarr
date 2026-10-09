package net.clickarr.playback.core

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.scheduling.CyclicLineupStrategy
import net.clickarr.core.scheduling.Lineups
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.testing.FakeMediaProvider
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TuneControllerTest {
    private val anchor = Instant.parse("2026-10-09T19:00:00Z")
    // TuneController waits 300 ms; advanceTimeBy(301) runs that task, so loads happen at +300 ms exactly.
    private val debounce = 300.milliseconds
    private val provider = FakeMediaProvider()

    // Four sitcom episodes from the fake library, 30-minute slots, sequential.
    private val entries = provider.library.episodes.take(4).map { LineupEntry(it.ref, it.runtime, it.title) }
    private val lineup = Lineups.create(LineupSnapshotId("l"), ChannelId("10"), entries, anchor)
    private val channel = Channel(
        id = ChannelId("10"), number = 10, name = "Sitcoms", source = ProgrammingSource.Explicit(emptyList()),
        order = OrderingMode.SEQUENTIAL, slotRounding = 30.minutes, seed = 1, lineup = lineup.id, anchor = anchor,
    )

    private class Harness(scope: TestScope, provider: FakeMediaProvider, lineup: net.clickarr.core.model.LineupSnapshot, start: Instant) {
        val engine = FakePlayerEngine { scope.testScheduler.currentTime }
        val clock = Clock { Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + scope.testScheduler.currentTime) }
        val controller = TuneController(
            strategy = CyclicLineupStrategy(),
            lineups = { if (it == lineup.id) lineup else null },
            providers = { provider },
            profile = DeviceProfile.Conservative,
            engine = engine,
            clock = clock,
            scope = scope.backgroundScope,
        )
    }

    @Test
    fun `tuning at 7-17 loads the first episode 17 minutes in and reports the slot position`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 17.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.loads.size shouldBe 1
        h.engine.loads.single().startAt shouldBe 17.minutes + debounce
        h.controller.state.value.airing.shouldNotBeNull().entry.title shouldBe entries[0].title
        h.controller.state.value.next.shouldNotBeNull().entry.title shouldBe entries[1].title
        h.controller.state.value.status shouldBe TuneStatus.Loading
        h.engine.emit(PlayerEvent.FirstFrame)
        runCurrent()
        h.controller.state.value.status shouldBe TuneStatus.Playing
        h.controller.state.value.slotPosition(h.clock.now()) shouldBe 17.minutes + debounce
    }

    @Test
    fun `rapid channel surfing only loads the channel the user settled on`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 1.minutes)
        val other = channel.copy(id = ChannelId("11"), number = 11)
        h.controller.tune(channel)
        advanceTimeBy(100)
        h.controller.tune(other)
        advanceTimeBy(100)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.loads.size shouldBe 1
        h.controller.state.value.channel?.number shouldBe 10
    }

    @Test
    fun `at the slot boundary the controller advances to the next program at offset zero`() = runTest {
        // Episode 0 runs 21 minutes in a 30-minute slot; tune in at 7:20 so content is still playing.
        val h = Harness(this, provider, lineup, anchor + 20.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.emit(PlayerEvent.FirstFrame)
        h.engine.loads.size shouldBe 1
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        runCurrent()
        h.engine.loads.size shouldBe 2
        h.engine.loads[1].startAt shouldBe 0.seconds
        h.controller.state.value.airing.shouldNotBeNull().entry.title shouldBe entries[1].title
    }

    @Test
    fun `a file that ends early shows filler until the slot ends, then advances`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 20.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.emit(PlayerEvent.FirstFrame)
        runCurrent()
        h.engine.emit(PlayerEvent.Ended)
        runCurrent()
        h.controller.state.value.status shouldBe TuneStatus.Filler
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        runCurrent()
        h.controller.state.value.airing.shouldNotBeNull().entry.title shouldBe entries[1].title
        h.controller.state.value.status shouldBe TuneStatus.Loading
    }

    @Test
    fun `tuning into the filler part of a slot does not load anything`() = runTest {
        // Episode 0 is 21 minutes in a 30-minute slot; at 7:25 we are in filler.
        val h = Harness(this, provider, lineup, anchor + 25.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.loads.size shouldBe 0
        h.controller.state.value.status shouldBe TuneStatus.Filler
    }

    @Test
    fun `drift beyond tolerance is corrected by a seek`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 5.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.emit(PlayerEvent.FirstFrame)
        runCurrent()
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()
        h.engine.seeks.size shouldBe 0 // in sync: no correction
        h.engine.stall(10.seconds)
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()
        h.engine.seeks.size shouldBe 1
        h.engine.seeks.single() shouldBe 5.minutes + 20.seconds + debounce
    }

    @Test
    fun `unavailable items show a reason and still advance at the boundary`() = runTest {
        val failing = FakeMediaProvider(failWith = ClickarrError.Unauthorized("Sign in again"))
        val h = Harness(this, failing, lineup, anchor + 20.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.controller.state.value.status.shouldBeInstanceOf<TuneStatus.Unavailable>().reason shouldBe "Sign in again"
        advanceTimeBy(11.minutes.inWholeMilliseconds)
        runCurrent()
        h.controller.state.value.airing.shouldNotBeNull().entry.title shouldBe entries[1].title
    }

    @Test
    fun `resume after pause goes back to the live position, not the paused one`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 2.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.controller.pause()
        h.controller.state.value.status shouldBe TuneStatus.Paused
        advanceTimeBy(3.minutes.inWholeMilliseconds)
        h.controller.resume()
        runCurrent()
        h.engine.loads.size shouldBe 2
        h.engine.loads.last().startAt shouldBe 5.minutes + debounce // 2 min + debounce + 3 min
    }

    @Test
    fun `ending playback releases the provider session`() = runTest {
        val h = Harness(this, provider, lineup, anchor + 1.minutes)
        h.controller.tune(channel)
        advanceTimeBy(301)
        runCurrent()
        h.engine.loads.single().shouldBeInstanceOf<PlaybackSource.DirectPlay>()
        h.controller.stop()
        runCurrent()
        h.engine.stops shouldBe 1
        h.controller.state.value.status shouldBe TuneStatus.Idle
    }
}
