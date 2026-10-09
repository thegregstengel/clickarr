package net.clickarr.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import net.clickarr.core.common.Log
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.scheduling.SCHEDULER_VERSION
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.core.secrets.SecretStore
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs
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
) : ViewModel() {
    data class General(val deviceName: String, val deviceId: String, val overlayTimeoutMs: Int)

    val general: StateFlow<General> = combine(prefs.deviceName, prefs.overlayTimeoutMs) { name, timeout ->
        General(name, prefs.deviceId, timeout)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), General("", prefs.deviceId, DevicePrefs.DEFAULT_OVERLAY_TIMEOUT_MS))

    val servers: StateFlow<List<ServerInfo>> = registry.providers.map { it.values.map { p -> p.server } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channelList: StateFlow<List<Channel>> =
        channels.channels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setOverlayTimeout(ms: Int) = viewModelScope.launch { prefs.setOverlayTimeoutMs(ms) }

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
