package net.clickarr.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.clickarr.core.model.Airing
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import net.clickarr.core.model.Channel
import net.clickarr.data.PlayerDeps
import net.clickarr.playback.core.TuneController
import net.clickarr.playback.core.TuneState
import net.clickarr.playback.media3.Media3PlayerEngine

/**
 * Owns the player engine and the tune controller for the lifetime of the player screen.
 * Launch behavior (proposal: "Launch Clickarr, television starts playing"): tune the last channel,
 * or the lowest-numbered one, as soon as the channel list is available.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val deps: PlayerDeps,
) : ViewModel() {
    val engine = Media3PlayerEngine(context, deps.okHttp, "Clickarr")

    private val controller = TuneController(
        strategy = deps.strategy,
        lineups = { deps.channels.lineup(it) },
        providers = { deps.provider(it) },
        profile = deps.profile,
        engine = engine,
        clock = deps.clock,
        scope = viewModelScope,
    )

    val tune: StateFlow<TuneState> = controller.state

    val channels: StateFlow<List<Channel>> =
        deps.channels.channels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _digits = MutableStateFlow("")
    val digits: StateFlow<String> = _digits.asStateFlow()

    /** The current channel's program now and the next few (design language 4, "Mini-guide"). */
    data class MiniGuide(val items: List<Airing>, val index: Int)

    private val _miniGuide = MutableStateFlow<MiniGuide?>(null)
    val miniGuide: StateFlow<MiniGuide?> = _miniGuide.asStateFlow()

    fun openMiniGuide() {
        val channel = tune.value.channel ?: return
        val now = tune.value.airing ?: return
        viewModelScope.launch {
            val lineup = deps.channels.lineup(channel.lineup) ?: return@launch
            val items = ArrayList<Airing>().apply { add(now) }
            var last = now
            while (items.size <= MINI_GUIDE_AHEAD) {
                last = deps.strategy.next(channel, lineup, last) ?: break
                items += last
            }
            hideOverlay()
            _miniGuide.value = MiniGuide(items, 0)
        }
    }

    fun moveMiniGuide(delta: Int) {
        _miniGuide.update { g -> g?.copy(index = (g.index + delta).coerceIn(0, g.items.lastIndex)) }
    }

    fun closeMiniGuide() {
        _miniGuide.value = null
    }

    private var hideJob: Job? = null
    private var digitJob: Job? = null
    private var overlayTimeoutMs = 5_000

    init {
        viewModelScope.launch {
            deps.prefs.overlayTimeoutMs.collect { overlayTimeoutMs = it }
        }
        viewModelScope.launch {
            deps.channels.applyPendingCutovers()
            val list = channels.first { it.isNotEmpty() }
            val last = deps.prefs.lastChannelId.first()
            tuneTo(list.firstOrNull { it.id.value == last } ?: list.first())
        }
    }

    fun now(): Instant = deps.clock.now()

    fun tuneTo(channel: Channel) {
        controller.tune(channel)
        viewModelScope.launch { deps.prefs.setLastChannelId(channel.id.value) }
        showOverlay()
    }

    fun channelUp() = step(+1)

    fun channelDown() = step(-1)

    private fun step(delta: Int) {
        val list = channels.value
        if (list.isEmpty()) return
        val current = tune.value.channel
        val index = list.indexOfFirst { it.id == current?.id }
        val next = if (index < 0) 0 else Math.floorMod(index + delta, list.size)
        tuneTo(list[next])
    }

    fun toggleOverlay() {
        if (_overlayVisible.value) hideOverlay() else showOverlay()
    }

    fun showOverlay() {
        _overlayVisible.value = true
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(overlayTimeoutMs.toLong())
            _overlayVisible.value = false
        }
    }

    fun hideOverlay() {
        hideJob?.cancel()
        _overlayVisible.value = false
    }

    /** Numeric entry: digits accumulate and commit after a short pause (design language 2.8). */
    fun enterDigit(d: Int) {
        _digits.value = (_digits.value + d).takeLast(4)
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            delay(DIGIT_COMMIT_MS)
            val number = _digits.value.toIntOrNull()
            _digits.value = ""
            val target = number?.let { n -> channels.value.firstOrNull { it.number == n } }
            if (target != null) tuneTo(target) else showOverlay()
        }
    }

    fun playPause() {
        if (engine.isPlaying) controller.pause() else controller.resume()
        showOverlay()
    }

    fun onResume() = controller.retune()

    fun onPause() = engine.pause()

    override fun onCleared() {
        controller.stop()
        engine.release()
    }

    companion object {
        private const val DIGIT_COMMIT_MS = 2_000L
    }
}

private const val MINI_GUIDE_AHEAD = 3
