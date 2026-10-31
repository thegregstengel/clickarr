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

    /**
     * Show or hide captions, preferring [language] (ISO 639-2). Returns false when the loaded source cannot switch
     * on its own (a transcode with captions burned in) and has to be loaded again for the change to show.
     */
    fun setCaptions(enabled: Boolean, language: String?): Boolean

    /**
     * Demuxed audio tracks on the loaded item (direct play). Empty for HLS / nothing loaded.
     * [selectAudio] returns false when the change needs a retune (transcode path).
     */
    fun audioTracks(): List<EngineAudioTrack> = emptyList()

    fun selectAudio(trackId: String?, language: String?): Boolean = true

    /** Current playback position within the loaded item, or null if nothing is loaded. */
    fun position(): Duration?

    val isPlaying: Boolean
}

/** One audio track the engine can select for direct play. */
data class EngineAudioTrack(val id: String, val language: String?, val label: String)

sealed interface PlayerEvent {
    /** First video frame rendered for the most recent [PlayerEngine.load]. */
    data object FirstFrame : PlayerEvent

    /** The loaded item reached its end on its own. */
    data object Ended : PlayerEvent

    data class Error(val message: String, val recoverable: Boolean = false) : PlayerEvent
}
