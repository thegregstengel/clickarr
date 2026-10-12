package net.clickarr.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import net.clickarr.core.common.Clock
import net.clickarr.data.DevicePrefs
import net.clickarr.data.SleepPrefs
import net.clickarr.provider.api.DeviceProfile

/** Settings, Playback. */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val prefs: DevicePrefs,
    private val sleep: SleepPrefs,
    private val clock: Clock,
    val profile: DeviceProfile,
) : ViewModel() {
    val watchReporting: StateFlow<String> =
        prefs.watchReporting.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), DevicePrefs.DEFAULT_WATCH_REPORTING)

    fun setWatchReporting(mode: String) = viewModelScope.launch { prefs.setWatchReporting(mode) }

    val captions: StateFlow<Boolean> = prefs.captions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    fun setCaptions(on: Boolean) = viewModelScope.launch { prefs.setCaptions(on) }

    val betweenPrograms: StateFlow<String> =
        prefs.betweenPrograms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), DevicePrefs.BETWEEN_PROMO)

    fun setBetweenPrograms(mode: String) = viewModelScope.launch { prefs.setBetweenPrograms(mode) }

    /** When playback stops, or null; a time already past counts as no timer. */
    val sleepAt: StateFlow<Instant?> = sleep.sleepAt
        .map { ms -> ms?.let(Instant::fromEpochMilliseconds)?.takeIf { it > clock.now() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun setSleepIn(minutes: Int?) = viewModelScope.launch {
        sleep.setSleepAt(minutes?.let { (clock.now() + it.minutes).toEpochMilliseconds() })
    }

    val stillWatchingHours: StateFlow<Int> =
        sleep.stillWatchingHours.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SleepPrefs.DEFAULT_STILL_WATCHING_HOURS)

    fun setStillWatchingHours(hours: Int) = viewModelScope.launch { sleep.setStillWatchingHours(hours) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
