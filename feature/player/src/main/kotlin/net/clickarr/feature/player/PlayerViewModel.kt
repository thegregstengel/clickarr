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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import net.clickarr.core.common.AppTime
import net.clickarr.core.model.Channel
import net.clickarr.core.model.inSeason
import net.clickarr.data.DevicePrefs
import net.clickarr.data.PlayerDeps
import net.clickarr.playback.core.TuneStatus
import net.clickarr.data.SleepPrefs
import net.clickarr.playback.core.TuneController
import net.clickarr.playback.core.TuneState
import net.clickarr.playback.core.WatchReporter
import net.clickarr.playback.media3.Media3PlayerEngine
import net.clickarr.provider.api.CaptionRequest

/**
 * Owns the player engine and the tune controller for the lifetime of the player screen.
 * Launch behavior (proposal: "Launch Clickarr, television starts playing"): tune the last channel,
 * or the lowest-numbered one, as soon as the channel list is available.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val deps: PlayerDeps,
    sleepPrefs: SleepPrefs,
) : ViewModel() {
    val engine = Media3PlayerEngine(context, deps.okHttp, "Clickarr")

    /** Captions on or off; starts from Settings, Playback (or the TV's own setting), switched by holding OK. */
    val captions: StateFlow<Boolean> = deps.prefs.captions.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val captionLanguage: String? = runCatching { java.util.Locale.getDefault().isO3Language }.getOrNull()

    private val controller = TuneController(
        strategy = deps.strategy,
        lineups = { deps.channels.lineup(it) },
        providers = { deps.provider(it) },
        profile = deps.profile,
        engine = engine,
        clock = deps.clock,
        scope = viewModelScope,
        captions = { if (captions.value) CaptionRequest(captionLanguage) else null },
    )

    val tune: StateFlow<TuneState> = controller.state

    private val watchMode: StateFlow<WatchReporter.Mode> = deps.prefs.watchReporting
        .map { name -> WatchReporter.Mode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WatchReporter.Mode.OFF }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WatchReporter.Mode.OFF)

    /** Settings, Plex or Jellyfin, "Tell <server> what you watched". */
    private val reporter = WatchReporter(
        providers = { deps.provider(it) },
        engine = engine,
        scope = viewModelScope,
        mode = { watchMode.value },
    )

    /** Settings, Playback, Between programs: the "Coming up next" promo, or the plain countdown card. */
    private val promoOn: StateFlow<Boolean> = deps.prefs.betweenPrograms.map { it == DevicePrefs.BETWEEN_PROMO }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val promos = PromoDirector(context, deps, viewModelScope)
    val promo: StateFlow<PromoDirector.Promo?> = promos.promo

    private val sleepAt = sleepPrefs.sleepAt.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val stillWatching =
        sleepPrefs.stillWatchingHours.stateIn(viewModelScope, SharingStarted.Eagerly, SleepPrefs.DEFAULT_STILL_WATCHING_HOURS)

    /** Sleep timer and "Are you still watching?" (Settings, Playback). */
    val sleep = SleepGuard(
        scope = viewModelScope,
        nowMs = { deps.clock.now().toEpochMilliseconds() },
        sleepAt = { sleepAt.value },
        stillWatchingHours = { stillWatching.value },
        isWatching = { controller.state.value.status == TuneStatus.Playing },
        onSleep = { controller.stop() },
        onWake = { controller.state.value.channel?.let(::tuneTo) },
        clearTimer = { sleepPrefs.setSleepAt(null) },
    )

    /** Minutes until the sleep timer, for the overlay, or null. */
    fun sleepInMinutes(): Long? = sleepAt.value?.let { (it - deps.clock.now().toEpochMilliseconds()) / MS_PER_MINUTE }?.takeIf { it >= 0 }

    init {
        sleep.start()
        promos.start(controller.state, promoOn)
        reporter.start(controller.state)
        viewModelScope.launch { captions.collect { on -> engine.setCaptions(on, captionLanguage) } }
    }

    /** "Captions on" for a moment after the viewer switches them. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    fun toggleCaptions() {
        val on = !captions.value
        viewModelScope.launch {
            deps.prefs.setCaptions(on)
            // Captions burned into a transcode need a fresh one; text tracks switch in place.
            if (!engine.setCaptions(on, captionLanguage)) controller.retune()
        }
        noticeJob?.cancel()
        _notice.value = if (on) "Captions on" else "Captions off"
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    /** OK: a short press shows or hides the overlay, a hold switches captions. */
    val ok = OkKeyGesture(onTap = { toggleOverlay() }, onHold = { toggleCaptions() })

    /** The channels in season today; surfing and numbers skip a Christmas channel in July. */
    val channels: StateFlow<List<Channel>> = deps.channels.channels
        .map { list -> list.filter { it.inSeason(AppTime.local(deps.clock.now()).date) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** Back closes the mini-guide, then the overlay; false means nothing was open and the caller should leave. */
    fun handleBack(): Boolean {
        when {
            _miniGuide.value != null -> closeMiniGuide()
            _overlayVisible.value -> hideOverlay()
            else -> return false
        }
        return true
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
        reporter.stop()
        promos.release()
        controller.stop()
        engine.release()
    }

    companion object {
        private const val DIGIT_COMMIT_MS = 2_000L
    }
}

private const val MINI_GUIDE_AHEAD = 3
private const val NOTICE_MS = 2_000L
private const val MS_PER_MINUTE = 60_000L
