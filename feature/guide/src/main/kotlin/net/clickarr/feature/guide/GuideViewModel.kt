package net.clickarr.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.clickarr.core.common.Clock
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs

/**
 * Builds the guide window (proposal 11.1): from the previous half-hour boundary before now, for a
 * few hours, for every channel. Recomputed when channels change and once a minute for the now-line.
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val strategy: ScheduleStrategy,
    private val clock: Clock,
    private val prefs: DevicePrefs,
) : ViewModel() {
    data class Row(val channel: Channel, val airings: List<Airing>)

    data class Window(
        val from: Instant,
        val to: Instant,
        val now: Instant,
        val rows: List<Row>,
        val currentChannelId: String?,
        val favorites: Set<String>,
    )

    private val _window = MutableStateFlow<Window?>(null)
    val window: StateFlow<Window?> = _window.asStateFlow()

    private val lineups = HashMap<LineupSnapshotId, LineupSnapshot>()

    init {
        viewModelScope.launch {
            combine(repository.channels, repository.favorites) { c, f -> c to f }
                .collect { (c, f) -> rebuild(c, f.map { it.value }.toSet()) }
        }
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                _window.value?.let { w -> rebuild(w.rows.map { it.channel }, w.favorites) }
            }
        }
    }

    private suspend fun rebuild(channels: List<Channel>, favorites: Set<String>) {
        val now = clock.now()
        val from = floorToHalfHour(now - 30.minutes)
        val to = from + WINDOW
        val rows = channels.sortedBy { it.number }.map { ch ->
            val lineup = lineups[ch.lineup] ?: repository.lineup(ch.lineup)?.also { lineups[ch.lineup] = it }
            Row(ch, lineup?.let { strategy.airingsBetween(ch, it, from, to) } ?: emptyList())
        }
        _window.value = Window(from, to, now, rows, prefs.lastChannelId.first(), favorites)
    }

    fun tune(channel: Channel, then: () -> Unit) {
        viewModelScope.launch {
            prefs.setLastChannelId(channel.id.value)
            then()
        }
    }

    private fun floorToHalfHour(t: Instant): Instant {
        val tz = TimeZone.currentSystemDefault()
        val local = t.toLocalDateTime(tz)
        val minute = if (local.minute < 30) 0 else 30
        return LocalDateTime(local.date, LocalTime(local.hour, minute)).toInstant(tz)
    }

    companion object {
        /** How far ahead the grid reaches; fast-forward jumps through it three hours at a time. */
        val WINDOW = 6.hours
    }
}
