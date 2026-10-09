package net.clickarr.playback.core

import kotlin.time.Duration
import kotlinx.coroutines.flow.SharedFlow
import net.clickarr.provider.api.PlaybackSource

/**
 * The slice of a media player the tune controller needs. Media3 implements it in playback:media3;
 * tests use FakePlayerEngine. Keeping this small is what lets tune-in logic run on the JVM.
 */
interface PlayerEngine {
    val events: SharedFlow<PlayerEvent>

    /** Replace whatever is playing with [source], seeking to [source.startAt] before the first frame. */
    fun load(source: PlaybackSource)

    fun play()

    fun pause()

    fun stop()

    fun seekTo(position: Duration)

    /** Current playback position within the loaded item, or null if nothing is loaded. */
    fun position(): Duration?

    val isPlaying: Boolean
}

sealed interface PlayerEvent {
    /** First video frame rendered for the most recent [PlayerEngine.load]. */
    data object FirstFrame : PlayerEvent

    /** The loaded item reached its end on its own. */
    data object Ended : PlayerEvent

    data class Error(val message: String, val recoverable: Boolean = false) : PlayerEvent
}
