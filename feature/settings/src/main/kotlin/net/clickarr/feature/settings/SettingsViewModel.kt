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
import net.clickarr.data.InstallEvents
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
    )

    val general: StateFlow<General> =
        combine(
            combine(prefs.deviceName, prefs.overlayTimeoutMs, prefs.uiScale, prefs.theme, prefs.guideHours) { a, b, c, d, e ->
                Base(a, b, c, d, e)
            },
            prefs.timeZoneId,
            prefs.autoTime,
        ) { base, zone, auto ->
            General(base.name, prefs.deviceId, base.timeout, base.scale, base.theme, base.hours, zone, auto)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            General(
                "", prefs.deviceId, DevicePrefs.DEFAULT_OVERLAY_TIMEOUT_MS, DevicePrefs.DEFAULT_UI_SCALE,
                DevicePrefs.DEFAULT_THEME, DevicePrefs.DEFAULT_GUIDE_HOURS, null, true,
            ),
        )

    private data class Base(val name: String, val timeout: Int, val scale: Float, val theme: String, val hours: Int)

    fun setTimeZone(id: String?) = viewModelScope.launch { prefs.setTimeZoneId(id) }

    fun setAutoTime(on: Boolean) = viewModelScope.launch {
        prefs.setAutoTime(on)
        if (on) timeSync.syncNow()
    }

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

        /** Downloaded and verified; [problem] is what the installer said the last time it was tried. */
        data class Ready(val latest: UpdateChecker.Latest, val file: File, val problem: String? = null) : UpdateState

        /** The TV has not yet allowed Clickarr to install apps; its settings page was opened. */
        data class NeedsPermission(val latest: UpdateChecker.Latest, val file: File) : UpdateState
        data class Installing(val latest: UpdateChecker.Latest, val file: File, val note: String) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        // The installer answers through a broadcast; fold its verdict into the state the About pane shows.
        viewModelScope.launch {
            InstallEvents.status.collect { status ->
                val installing = _updateState.value as? UpdateState.Installing ?: return@collect
                _updateState.value = when (status) {
                    InstallEvents.Status.Idle, InstallEvents.Status.Started -> installing
                    InstallEvents.Status.Confirming -> installing.copy(note = "Confirm the install on the TV's screen.")
                    InstallEvents.Status.Installed -> installing.copy(note = "Installed. Clickarr restarts on the new build.")
                    is InstallEvents.Status.Failed -> UpdateState.Ready(installing.latest, installing.file, status.message)
                }
            }
        }
    }

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
                is Outcome.Success -> when {
                    r.value.versionCode <= currentVersionCode -> UpdateState.UpToDate(r.value.versionName)
                    !updates.signedLikeThisInstall(r.value) -> UpdateState.Failed(differentKey(r.value))
                    else -> UpdateState.Available(r.value)
                }
                is Outcome.Failure -> UpdateState.Failed(r.error.message)
            }
        }
    }

    private fun differentKey(latest: UpdateChecker.Latest): String =
        "${latest.versionName} (build ${latest.versionCode}) is available, but it is signed with a different key than " +
            "the Clickarr on this TV, so the system would refuse to update over it. Uninstall Clickarr and install once " +
            "from clickarr.net/${latest.channel}; after that, updates install in place."

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

    /** Asks the system to install the verified file; first makes sure the TV lets Clickarr install at all. */
    fun installUpdate() {
        val (latest, file) = when (val s = _updateState.value) {
            is UpdateState.Ready -> s.latest to s.file
            is UpdateState.NeedsPermission -> s.latest to s.file
            else -> return
        }
        if (!updates.canInstall()) {
            _updateState.value = UpdateState.NeedsPermission(latest, file)
            updates.openInstallPermission()
            return
        }
        _updateState.value = UpdateState.Installing(latest, file, "Handing the build to the installer…")
        viewModelScope.launch { updates.install(file) }
    }

    fun setOverlayTimeout(ms: Int) = viewModelScope.launch { prefs.setOverlayTimeoutMs(ms) }

    fun setUiScale(scale: Float) = viewModelScope.launch { prefs.setUiScale(scale) }

    fun setTheme(name: String) = viewModelScope.launch { prefs.setTheme(name) }

    fun setGuideHours(hours: Int) = viewModelScope.launch { prefs.setGuideHours(hours) }

    /** Settings, Channels, "Keep lineups current": the daily re-read and what the last one found. */
    data class RefreshInfo(val automatic: Boolean, val lastAt: Instant?, val note: String?)

    val refreshInfo: StateFlow<RefreshInfo> =
        combine(prefs.autoRefresh, prefs.lastAutoRefreshAt, prefs.lastAutoRefreshNote) { auto, at, note ->
            RefreshInfo(auto, at?.let(Instant::fromEpochMilliseconds), note)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefreshInfo(true, null, null))

    fun setAutoRefresh(on: Boolean) = viewModelScope.launch { prefs.setAutoRefresh(on) }

    val watchReporting: StateFlow<String> =
        prefs.watchReporting.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicePrefs.DEFAULT_WATCH_REPORTING)

    fun setWatchReporting(mode: String) = viewModelScope.launch { prefs.setWatchReporting(mode) }

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
