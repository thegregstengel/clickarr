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
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.data.ProviderRegistry
import net.clickarr.core.model.Show
import net.clickarr.core.model.Movie
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Episode
import net.clickarr.core.common.Outcome
import kotlinx.coroutines.Job

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
    private val registry: ProviderRegistry,
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

    /** What the preview card shows for the focused program: synopsis and a thumbnail URL from the server. */
    data class Preview(val ref: MediaRef, val summary: String?, val thumbUrl: String?)

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()
    private val previews = object : LinkedHashMap<MediaRef, Preview>(PREVIEW_CACHE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaRef, Preview>?) = size > PREVIEW_CACHE
    }
    private var previewJob: Job? = null

    /** Called as focus moves; waits a moment so a fast scroll does not fire a request per cell. */
    fun focus(airing: Airing) {
        val ref = airing.entry.ref
        val cached = previews[ref]
        if (cached != null) {
            _preview.value = cached
            return
        }
        _preview.value = null
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val provider = registry.get(ref.provider) ?: return@launch
            val item = (provider.items(listOf(ref)) as? Outcome.Success)?.value?.firstOrNull() ?: return@launch
            val summary = when (item) {
                is Episode -> item.summary
                is Movie -> item.summary
                is Show -> item.summary
                else -> null
            }
            val art = item.artwork.thumb ?: item.artwork.poster
            val preview = Preview(ref, summary, art?.let { provider.artworkUrl(it, ArtworkSize.THUMB) })
            previews[ref] = preview
            _preview.value = preview
        }
    }

    private val lineups = HashMap<LineupSnapshotId, LineupSnapshot>()
    private var hours = DevicePrefs.DEFAULT_GUIDE_HOURS

    init {
        viewModelScope.launch {
            combine(repository.channels, repository.favorites) { c, f -> c to f }
                .collect { (c, f) -> rebuild(c, f.map { it.value }.toSet()) }
        }
        viewModelScope.launch {
            prefs.guideHours.collect { h ->
                hours = h
                _window.value?.let { w -> rebuild(w.rows.map { it.channel }, w.favorites) }
            }
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
        val to = from + hours.hours
        val rows = channels.sortedBy { it.number }.map { ch ->
            val lineup = lineups[ch.lineup] ?: repository.lineup(ch.lineup)?.also { lineups[ch.lineup] = it }
            Row(ch, lineup?.let { strategy.airingsBetween(ch, it, from, to) } ?: emptyList())
        }
        _window.value = Window(from, to, now, rows, prefs.lastChannelId.first(), favorites)
    }

    fun toggleFavorite(id: ChannelId) {
        viewModelScope.launch {
            val favorites = _window.value?.favorites.orEmpty()
            repository.setFavorite(id, id.value !in favorites)
        }
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

}

private const val PREVIEW_CACHE = 200
private const val PREVIEW_DEBOUNCE_MS = 250L
private const val LOAD_FACTOR = 0.75f
