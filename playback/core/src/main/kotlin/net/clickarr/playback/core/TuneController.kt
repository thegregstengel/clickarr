package net.clickarr.playback.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.PlaybackSource

/**
 * Turns "this channel, right now" into player commands and keeps the illusion of a live broadcast:
 *
 *  - tune-in computes the current airing and loads it at `now - airing.start` (proposal 10.2)
 *  - at `airing.end` it advances to the next airing on the same channel (10.3)
 *  - a file that ends early shows filler until the slot ends; a file that is unavailable shows
 *    an unavailable card until the slot ends (10.3, 15.4)
 *  - every [driftInterval] it compares player position to the schedule and re-seeks if off by
 *    more than [driftTolerance] (10.1)
 *  - rapid channel changes are debounced so intermediate channels never start loading (10.2)
 *  - returning to a channel never resumes; it always recomputes (10.4)
 *
 * No Android here. The clock and player are injected so tests run on virtual time.
 */
class TuneController(
    private val strategy: ScheduleStrategy,
    private val lineups: suspend (LineupSnapshotId) -> LineupSnapshot?,
    private val providers: (ProviderId) -> MediaProvider?,
    private val profile: DeviceProfile,
    private val engine: PlayerEngine,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val debounce: Duration = 300.milliseconds,
    private val driftInterval: Duration = 10.seconds,
    private val driftTolerance: Duration = 5.seconds,
) {
    private val _state = MutableStateFlow(TuneState())
    val state: StateFlow<TuneState> = _state.asStateFlow()

    private var tuneJob: Job? = null
    private var sessionJob: Job? = null
    private var currentSource: PlaybackSource? = null
    private var currentProvider: MediaProvider? = null

    init {
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    PlayerEvent.FirstFrame -> _state.update {
                        if (it.status == TuneStatus.Loading) it.copy(status = TuneStatus.Playing) else it
                    }
                    PlayerEvent.Ended -> onEndedEarly()
                    is PlayerEvent.Error -> onPlayerError(event)
                }
            }
        }
    }

    /** Change channel. Calls arriving within [debounce] of each other only act on the last one. */
    fun tune(channel: Channel) {
        _state.update { it.copy(channel = channel, airing = null, next = null, status = TuneStatus.Loading) }
        tuneJob?.cancel()
        tuneJob = scope.launch {
            delay(debounce)
            startSession(channel)
        }
    }

    /** Recompute the live position for the current channel, for example after a pause or on resume. */
    fun retune() {
        val channel = _state.value.channel ?: return
        tuneJob?.cancel()
        tuneJob = scope.launch { startSession(channel) }
    }

    fun stop() {
        tuneJob?.cancel()
        sessionJob?.cancel()
        releaseCurrent()
        engine.stop()
        _state.update { TuneState(channel = it.channel) }
    }

    /** Pausing is allowed; resuming goes back to the live position rather than where it was paused (10.6). */
    fun pause() {
        engine.pause()
        _state.update { it.copy(status = TuneStatus.Paused) }
    }

    fun resume() = retune()

    private suspend fun startSession(channel: Channel) {
        sessionJob?.cancel()
        releaseCurrent()
        val lineup = lineups(channel.lineup)
        if (lineup == null) {
            _state.update { it.copy(status = TuneStatus.Unavailable("Channel has no lineup yet")) }
            return
        }
        val now = clock.now()
        val airing = strategy.airingAt(channel, lineup, now)
        if (airing == null) {
            _state.update { it.copy(status = TuneStatus.Unavailable("Nothing scheduled")) }
            return
        }
        sessionJob = scope.launch { runAiring(channel, lineup, airing) }
    }

    private suspend fun runAiring(channel: Channel, lineup: LineupSnapshot, airing: Airing) {
        val next = strategy.next(channel, lineup, airing)
        _state.update { it.copy(channel = channel, airing = airing, next = next, status = TuneStatus.Loading, position = Duration.ZERO) }

        val now = clock.now()
        val offset = (now - airing.start).coerceAtLeast(Duration.ZERO)
        if (now < airing.contentEnd) {
            when (val loaded = loadContent(airing, offset)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> _state.update { it.copy(status = TuneStatus.Unavailable(loaded.error.message)) }
            }
        } else {
            engine.stop()
            _state.update { it.copy(status = TuneStatus.Filler) }
        }

        // Boundary and drift loop. Runs until the slot ends, then advances.
        while (true) {
            val t = clock.now()
            val remaining = airing.end - t
            if (remaining <= Duration.ZERO) break
            delay(minOf(remaining, driftInterval))
            checkDrift(airing)
        }
        releaseCurrent()
        if (next != null) {
            runAiring(channel, lineup, next)
        } else {
            _state.update { it.copy(status = TuneStatus.Unavailable("End of schedule")) }
        }
    }

    private suspend fun loadContent(airing: Airing, offset: Duration): Outcome<Unit> {
        val provider = providers(airing.entry.ref.provider)
            ?: return Outcome.Failure(ClickarrError.Unsupported("No server for ${airing.entry.ref.provider.value}"))
        return when (val source = provider.playbackSource(airing.entry.ref, profile, offset)) {
            is Outcome.Success -> {
                currentSource = source.value
                currentProvider = provider
                engine.load(source.value)
                engine.play()
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> {
                Log.w(TAG) { "playbackSource failed for ${airing.entry.title}: ${source.error.message}" }
                source
            }
        }
    }

    private fun checkDrift(airing: Airing) {
        val s = _state.value
        if (s.status != TuneStatus.Playing) return
        val expected = clock.now() - airing.start
        val actual = engine.position() ?: return
        _state.update { it.copy(position = actual) }
        // A transcode carries its offset server-side, so the player's own position is relative to the
        // transcode start and cannot be compared to the schedule. Direct play can be corrected in place.
        if (currentSource is PlaybackSource.Hls) return
        val delta = expected - actual
        if (delta.absoluteValue > driftTolerance) {
            Log.i(TAG) { "drift ${delta.inWholeSeconds}s on ${airing.entry.title}, re-seeking" }
            engine.seekTo(expected)
        }
    }

    private fun onEndedEarly() {
        val s = _state.value
        if (s.status != TuneStatus.Playing && s.status != TuneStatus.Loading) return
        _state.update { it.copy(status = TuneStatus.Filler) }
    }

    private fun onPlayerError(error: PlayerEvent.Error) {
        Log.w(TAG) { "player error: ${error.message}" }
        _state.update { it.copy(status = TuneStatus.Unavailable(error.message)) }
    }

    private fun releaseCurrent() {
        val src = currentSource ?: return
        val provider = currentProvider
        currentSource = null
        currentProvider = null
        if (provider != null) scope.launch { provider.endPlayback(src) }
    }

    companion object {
        private const val TAG = "Tune"
    }
}

data class TuneState(
    val channel: Channel? = null,
    val airing: Airing? = null,
    val next: Airing? = null,
    val status: TuneStatus = TuneStatus.Idle,
    /** Last observed player position. Updated on the drift interval, not every frame. */
    val position: Duration = Duration.ZERO,
) {
    /** Where the channel is within the current slot, by the schedule rather than the player. */
    fun slotPosition(now: Instant): Duration? = airing?.let { (now - it.start).coerceIn(Duration.ZERO, it.end - it.start) }
}

sealed interface TuneStatus {
    data object Idle : TuneStatus
    data object Loading : TuneStatus
    data object Playing : TuneStatus
    data object Paused : TuneStatus

    /** Content ended before the slot did; the filler card is showing. */
    data object Filler : TuneStatus

    data class Unavailable(val reason: String) : TuneStatus
}
