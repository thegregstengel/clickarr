package net.clickarr.feature.player

import android.os.SystemClock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sleep timer and "Are you still watching?" for the player (Settings, Playback). Every few seconds it checks
 * the timer against the app clock and the time since the last key press against the configured hours. When the
 * timer fires, or nobody answers the question within a minute, it calls [onSleep]; the next key press wakes it.
 */
class SleepGuard(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val sleepAt: () -> Long?,
    private val stillWatchingHours: () -> Int,
    private val isWatching: () -> Boolean,
    private val onSleep: () -> Unit,
    private val onWake: () -> Unit,
    private val clearTimer: suspend () -> Unit,
) {
    sealed interface State {
        data object Awake : State

        /** The question is on screen; with no answer by [deadlineMs] (elapsed realtime), playback stops. */
        data class Asking(val deadlineMs: Long) : State

        data object Asleep : State
    }

    private val _state = MutableStateFlow<State>(State.Awake)
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastInput = SystemClock.elapsedRealtime()

    fun start() {
        scope.launch {
            while (true) {
                delay(CHECK)
                check()
            }
        }
    }

    /** A key was pressed. True when it was used to answer the question or wake up, so it should go no further. */
    fun onInput(): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        return when (_state.value) {
            State.Awake -> false
            is State.Asking -> {
                _state.value = State.Awake
                true
            }
            State.Asleep -> {
                _state.value = State.Awake
                onWake()
                true
            }
        }
    }

    private suspend fun check() {
        val s = _state.value
        if (s == State.Asleep) return
        val timer = sleepAt()
        val elapsed = SystemClock.elapsedRealtime()
        when {
            timer != null && nowMs() >= timer -> {
                clearTimer()
                sleep()
            }
            s is State.Asking && elapsed >= s.deadlineMs -> sleep()
            s == State.Awake && isWatching() && idleTooLong(elapsed) -> _state.value = State.Asking(elapsed + ANSWER.inWholeMilliseconds)
        }
    }

    private fun idleTooLong(elapsed: Long): Boolean {
        val hours = stillWatchingHours()
        return hours > 0 && elapsed - lastInput >= hours.hours.inWholeMilliseconds
    }

    private fun sleep() {
        _state.value = State.Asleep
        onSleep()
    }

    private companion object {
        val CHECK = 5.seconds
        val ANSWER = 60.seconds
    }
}
