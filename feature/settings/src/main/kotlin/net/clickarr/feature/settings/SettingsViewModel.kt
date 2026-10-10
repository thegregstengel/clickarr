package net.clickarr.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.Log
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.scheduling.SCHEDULER_VERSION
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.core.secrets.SecretStore
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs
import net.clickarr.data.UpdateChecker
import kotlinx.datetime.Instant
import net.clickarr.data.TimeSync
import net.clickarr.data.ProviderRegistry
import net.clickarr.provider.api.DeviceProfile

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val channels: ChannelRepository,
    private val prefs: DevicePrefs,
    private val secrets: SecretStore,
    private val strategy: ScheduleStrategy,
    private val clock: Clock,
    val profile: DeviceProfile,
    private val updates: UpdateChecker,
    private val timeSync: TimeSync,
) : ViewModel() {
    data class General(
        val deviceName: String,
        val deviceId: String,
        val overlayTimeoutMs: Int,
        val uiScale: Float,
        val theme: String,
        val guideHours: Int,
        val timeZoneId: String?,
        val autoTime: Boolean,
        val clockOffsetMs: Long,
    )

    val general: StateFlow<General> =
        combine(
            combine(prefs.deviceName, prefs.overlayTimeoutMs, prefs.uiScale, prefs.theme, prefs.guideHours) { a, b, c, d, e ->
                Base(a, b, c, d, e)
            },
            prefs.timeZoneId,
            prefs.autoTime,
            prefs.manualClockOffsetMs,
        ) { base, zone, auto, offset ->
            General(base.name, prefs.deviceId, base.timeout, base.scale, base.theme, base.hours, zone, auto, offset)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            General(
                "", prefs.deviceId, DevicePrefs.DEFAULT_OVERLAY_TIMEOUT_MS, DevicePrefs.DEFAULT_UI_SCALE,
                DevicePrefs.DEFAULT_THEME, DevicePrefs.DEFAULT_GUIDE_HOURS, null, true, 0L,
            ),
        )

    private data class Base(val name: String, val timeout: Int, val scale: Float, val theme: String, val hours: Int)

    fun setTimeZone(id: String?) = viewModelScope.launch { prefs.setTimeZoneId(id) }

    fun setAutoTime(on: Boolean) = viewModelScope.launch {
        prefs.setAutoTime(on)
        if (on) timeSync.syncNow()
    }

    fun nudgeClock(deltaMs: Long) = viewModelScope.launch { prefs.setManualClockOffsetMs(general.value.clockOffsetMs + deltaMs) }

    fun resetClock() = viewModelScope.launch { prefs.setManualClockOffsetMs(0L) }

    fun now(): Instant = clock.now()

    val servers: StateFlow<List<ServerInfo>> = registry.providers.map { it.values.map { p -> p.server } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channelList: StateFlow<List<Channel>> =
        channels.channels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<Set<ChannelId>> =
        channels.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class UpToDate(val versionName: String) : UpdateState
        data class Available(val latest: UpdateChecker.Latest) : UpdateState
        data class Downloading(val latest: UpdateChecker.Latest, val progress: Float) : UpdateState
        data class Ready(val latest: UpdateChecker.Latest, val file: File) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val updateChannel: StateFlow<String> =
        prefs.updateChannel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicePrefs.DEFAULT_UPDATE_CHANNEL)

    fun setUpdateChannel(channel: String) = viewModelScope.launch {
        prefs.setUpdateChannel(channel)
        _updateState.value = UpdateState.Idle
    }

    /** Manual check against GitHub releases on the chosen channel (About and Updates). Never automatic. */
    fun checkForUpdates(currentVersionCode: Int) {
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            _updateState.value = when (val r = updates.latest(updateChannel.value)) {
                is Outcome.Success -> if (r.value.versionCode > currentVersionCode) {
                    UpdateState.Available(r.value)
                } else {
                    UpdateState.UpToDate(r.value.versionName)
                }
                is Outcome.Failure -> UpdateState.Failed(r.error.message)
            }
        }
    }

    fun downloadUpdate() {
        val latest = (_updateState.value as? UpdateState.Available)?.latest ?: return
        _updateState.value = UpdateState.Downloading(latest, 0f)
        viewModelScope.launch {
            _updateState.value = when (val r = updates.download(latest) { p -> _updateState.value = UpdateState.Downloading(latest, p) }) {
                is Outcome.Success -> UpdateState.Ready(latest, r.value)
                is Outcome.Failure -> UpdateState.Failed(r.error.message)
            }
        }
    }

    fun installUpdate() {
        val ready = _updateState.value as? UpdateState.Ready ?: return
        updates.install(ready.file)
    }

    fun setOverlayTimeout(ms: Int) = viewModelScope.launch { prefs.setOverlayTimeoutMs(ms) }

    fun setUiScale(scale: Float) = viewModelScope.launch { prefs.setUiScale(scale) }

    fun setTheme(name: String) = viewModelScope.launch { prefs.setTheme(name) }

    fun setGuideHours(hours: Int) = viewModelScope.launch { prefs.setGuideHours(hours) }

    fun disconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            servers.value.forEach { registry.remove(it.providerId) }
            onDone()
        }
    }

    fun refreshAllLineups() {
        viewModelScope.launch {
            val n = channels.refreshAll()
            _message.value = "Refreshed $n channel${if (n == 1) "" else "s"}. Changes apply at each channel's next program."
        }
    }

    /** Everything a bug report needs, as plain lines (design language 5, "Diagnostics"). */
    data class Diagnostics(val lines: List<String>, val log: List<String>)

    private val _diagnostics = MutableStateFlow(Diagnostics(emptyList(), emptyList()))
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    fun loadDiagnostics() {
        viewModelScope.launch {
            val now = clock.now()
            val lines = buildList {
                add("time (UTC)        $now")
                add("device id         ${prefs.deviceId}")
                val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                add("device            $device")
                add("scheduler version $SCHEDULER_VERSION")
                add("secret store      ${if (secrets.usingSoftwareKey) "software key (Keystore unavailable)" else "Android Keystore"}")
                servers.value.forEach { add("server            ${it.name} ${it.baseUrl} v${it.version ?: "?"} id=${it.serverIdentity}") }
                add("")
                for (ch in channels.all()) {
                    val lineup = channels.lineup(ch.lineup)
                    val airing = lineup?.let { strategy.airingAt(ch, it, now) }
                    add("channel ${ch.number} ${ch.name}")
                    val hash = lineup?.contentHash?.take(12) ?: "-"
                    add("  lineup ${ch.lineup.value.take(8)} entries=${lineup?.entries?.size ?: 0} hash=$hash")
                    add("  anchor ${ch.anchor} order=${ch.order} rounding=${ch.slotRounding} seed=${ch.seed}")
                    if (airing != null) {
                        val sub = airing.entry.subtitle ?: ""
                        add("  now    cycle=${airing.cycle} index=${airing.indexInCycle} ${airing.entry.title} / $sub")
                        add("  slot   ${airing.start} .. ${airing.end}  elapsed=${now - airing.start}")
                    } else {
                        add("  now    nothing airing")
                    }
                    ch.pendingLineup?.let { add("  pending ${it.value.take(8)} at ${ch.pendingAt}") }
                }
            }
            _diagnostics.value = Diagnostics(lines, Log.recent().takeLast(60))
        }
    }
}
