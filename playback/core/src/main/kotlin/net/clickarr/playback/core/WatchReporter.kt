package net.clickarr.playback.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.ProviderId
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.PlaybackState

/**
 * Tells the server what was watched (Settings, Playback, "Tell Plex what you watched"). Linear TV is
 * not on-demand: tuning in with five minutes left is not watching the episode, and a channel left on
 * overnight should not mark forty episodes. So an item counts as watched only when this TV actually
 * played [WATCHED_FRACTION] of it in one sitting, measured from the player's own position, and seeks or
 * tune-ins do not count. [Mode.PROGRESS] also sends the running position, which puts the episode in the
 * server's Continue Watching; [Mode.WATCHED] only ever marks whole episodes.
 */
class WatchReporter(
    private val providers: (ProviderId) -> MediaProvider?,
    private val engine: PlayerEngine,
    private val scope: CoroutineScope,
    private val mode: () -> Mode,
    private val interval: Duration = 10.seconds,
) {
    enum class Mode { OFF, WATCHED, PROGRESS }

    private class Sitting(val ref: MediaRef, val duration: Duration) {
        var watched: Duration = Duration.ZERO
        var last: Duration? = null
        var marked = false
    }

    private var sitting: Sitting? = null
    private var jobs: List<Job> = emptyList()

    fun start(state: StateFlow<TuneState>) {
        stop()
        jobs = listOf(
            scope.launch {
                state.map { it.airing?.entry }.distinctUntilChanged().collect { entry -> switchTo(entry) }
            },
            scope.launch {
                while (true) {
                    delay(interval)
                    if (state.value.status == TuneStatus.Playing) tick()
                }
            },
        )
    }

    /** Closes the current sitting (player screen going away) and stops observing. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val open = sitting ?: return
        sitting = null
        scope.launch { finish(open) }
    }

    private suspend fun switchTo(entry: LineupEntry?) {
        sitting?.let { finish(it) }
        sitting = entry?.let { Sitting(it.ref, it.duration) }
    }

    private suspend fun tick() {
        val s = sitting ?: return
        val position = engine.position() ?: return
        s.last?.let { last ->
            val delta = position - last
            // Only time that passed at playback speed counts; a seek or a reload is not watching.
            if (delta >= Duration.ZERO && delta <= interval * SEEK_SLACK) s.watched += delta
        }
        s.last = position
        when (mode()) {
            Mode.OFF -> return
            Mode.PROGRESS -> send(s.ref) { reportProgress(s.ref, position, PlaybackState.PLAYING) }
            Mode.WATCHED -> Unit
        }
        markIfWatched(s)
    }

    private suspend fun finish(s: Sitting) {
        if (mode() == Mode.OFF) return
        markIfWatched(s)
        if (mode() == Mode.PROGRESS) send(s.ref) { reportProgress(s.ref, s.last ?: Duration.ZERO, PlaybackState.STOPPED) }
    }

    private suspend fun markIfWatched(s: Sitting) {
        if (s.marked || s.duration <= Duration.ZERO) return
        if (s.watched < s.duration * WATCHED_FRACTION) return
        s.marked = true
        send(s.ref) { markPlayed(s.ref) }
    }

    private suspend fun send(ref: MediaRef, call: suspend MediaProvider.() -> Outcome<Unit>) {
        val provider = providers(ref.provider) ?: return
        when (val r = provider.call()) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> Log.w(TAG) { "watch report failed: ${r.error.message}" }
        }
    }

    companion object {
        private const val TAG = "Watch"

        /** How much of an item this TV must play, in one sitting, before it is marked watched. */
        const val WATCHED_FRACTION = 0.85

        /** A position jump larger than this many intervals is a seek, not time watched. */
        private const val SEEK_SLACK = 3
    }
}
