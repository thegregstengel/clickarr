package net.clickarr.playback.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.clickarr.provider.api.PlaybackSource

/** Position advances with the injected (virtual) clock while playing, so drift checks behave like a real player. */
class FakePlayerEngine(private val nowMs: () -> Long = { 0L }) : PlayerEngine {
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PlayerEvent> = _events

    val loads = mutableListOf<PlaybackSource>()
    val seeks = mutableListOf<Duration>()
    var stops = 0
    override var isPlaying = false
    private var basePos: Duration? = null
    private var baseTime = 0L

    override fun load(source: PlaybackSource) {
        loads += source
        basePos = source.startAt
        baseTime = nowMs()
    }

    override fun play() {
        basePos = position()
        baseTime = nowMs()
        isPlaying = true
    }

    override fun pause() {
        basePos = position()
        isPlaying = false
    }

    override fun stop() {
        stops++
        isPlaying = false
        basePos = null
    }

    override fun seekTo(position: Duration) {
        seeks += position
        basePos = position
        baseTime = nowMs()
    }

    override fun position(): Duration? = basePos?.let { if (isPlaying) it + (nowMs() - baseTime).milliseconds else it }

    /** Simulate a buffering stall: the position falls behind the wall clock by [by]. */
    fun stall(by: Duration) {
        basePos = position()?.minus(by)
        baseTime = nowMs()
    }

    fun emit(event: PlayerEvent) = check(_events.tryEmit(event))
}
