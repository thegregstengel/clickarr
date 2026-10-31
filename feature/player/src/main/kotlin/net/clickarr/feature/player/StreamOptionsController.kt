package net.clickarr.feature.player

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.MediaRef
import net.clickarr.data.DevicePrefs
import net.clickarr.playback.core.PlayerEngine
import net.clickarr.playback.core.TuneController
import net.clickarr.playback.core.TuneState
import net.clickarr.provider.api.AudioOption
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.PlaybackChoice
import net.clickarr.provider.api.PlaybackOptions
import net.clickarr.provider.api.VideoOption

/**
 * In-player Audio & video panel state. Owned by [PlayerViewModel] but kept as its own type so Detekt's
 * TooManyFunctions does not grow with every stream action.
 */
class StreamOptionsController(
    private val scope: CoroutineScope,
    private val prefs: DevicePrefs,
    private val engine: PlayerEngine,
    private val controller: TuneController,
    private val providers: (MediaRef) -> MediaProvider?,
    private val tune: () -> TuneState,
    private val preferredAudioLanguage: () -> String?,
) {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _options = MutableStateFlow(PlaybackOptions())
    val options: StateFlow<PlaybackOptions> = _options.asStateFlow()

    private val _selectedMedia = MutableStateFlow<String?>(null)
    val selectedMedia: StateFlow<String?> = _selectedMedia.asStateFlow()

    private val _selectedAudio = MutableStateFlow<String?>(null)
    val selectedAudio: StateFlow<String?> = _selectedAudio.asStateFlow()

    private var choiceMedia: String? = null
    private var choiceAudio: String? = null
    private var sittingRef: MediaRef? = null

    fun currentChoice(): PlaybackChoice? {
        val lang = preferredAudioLanguage()
        if (choiceMedia == null && choiceAudio == null && lang == null) return null
        return PlaybackChoice(choiceMedia, choiceAudio, lang)
    }

    fun open() {
        val airing = tune().airing ?: return
        if (sittingRef != airing.entry.ref) {
            choiceMedia = null
            choiceAudio = null
            sittingRef = airing.entry.ref
        }
        _open.value = true
        scope.launch { loadOptions(airing.entry.ref, airing.entry.duration) }
    }

    fun close() {
        _open.value = false
    }

    /** True when the panel was open and is now closed. */
    fun handleBack(): Boolean {
        if (!_open.value) return false
        close()
        return true
    }

    fun pickVideo(id: String?) {
        choiceMedia = id?.takeUnless { it == AUTO_VIDEO_ID }
        _selectedMedia.value = choiceMedia
        scope.launch {
            controller.retune()
            reloadAudios()
        }
    }

    fun pickAudio(id: String?, language: String?) {
        choiceAudio = id
        _selectedAudio.value = id
        scope.launch {
            language?.let { prefs.setLastAudioLanguage(it) }
            // Direct play can switch in place; HLS / burned paths need a retune with audioStreamID.
            if (!engine.selectAudio(id, language)) controller.retune()
        }
    }

    private suspend fun loadOptions(ref: MediaRef, slotDuration: Duration) {
        val provider = providers(ref) ?: return
        when (val result = provider.playbackOptions(ref)) {
            is Outcome.Failure -> Log.w(TAG) { "playbackOptions failed: ${result.error.message}" }
            is Outcome.Success -> {
                val opts = result.value
                val videos = withAuto(opts.videos, slotDuration)
                val audios = opts.audios.ifEmpty {
                    engine.audioTracks().map { AudioOption(it.id, it.label, it.language) }
                }
                _options.value = PlaybackOptions(videos, audios)
                _selectedMedia.value = choiceMedia
                _selectedAudio.value = choiceAudio
            }
        }
    }

    private suspend fun reloadAudios() {
        val ref = tune().airing?.entry?.ref ?: return
        val provider = providers(ref) ?: return
        when (val result = provider.playbackOptions(ref)) {
            is Outcome.Success -> {
                val audios = result.value.audios.ifEmpty {
                    engine.audioTracks().map { AudioOption(it.id, it.label, it.language) }
                }
                _options.update { it.copy(audios = audios) }
            }
            is Outcome.Failure -> Unit
        }
    }

    companion object {
        private const val TAG = "Streams"
        internal val DURATION_SLACK = 2.seconds
    }
}

/** Auto first; log when a non-first version matches the lineup duration (preferred Auto target). */
private fun withAuto(videos: List<VideoOption>, slotDuration: Duration): List<VideoOption> {
    if (videos.isEmpty()) return emptyList()
    val match = videos.firstOrNull { v ->
        v.duration?.let { (it - slotDuration).absoluteValue <= StreamOptionsController.DURATION_SLACK } == true
    }
    if (match != null && match != videos.first()) {
        Log.i("Streams") { "duration match for Auto preference: ${match.id}" }
    }
    return listOf(VideoOption(id = AUTO_VIDEO_ID, label = "Auto (device profile)")) + videos
}

internal const val AUTO_VIDEO_ID = "auto"
