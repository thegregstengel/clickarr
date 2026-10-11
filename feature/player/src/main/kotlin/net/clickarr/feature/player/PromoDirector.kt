package net.clickarr.feature.player

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Episode
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.Movie
import net.clickarr.core.model.Show
import net.clickarr.data.PlayerDeps
import net.clickarr.playback.core.TuneState
import net.clickarr.playback.core.TuneStatus
import net.clickarr.playback.media3.Media3PlayerEngine
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.PlaybackSource

/**
 * The "Coming up next" promo that fills the gap at the end of a padded slot (Settings, Playback, Between programs).
 * Everything comes from what Plex already has: the next program's backdrop, synopsis, and the show's theme music,
 * which plays quietly on its own small player while the gap lasts. Tuning in mid-gap starts the theme at the
 * matching second, so every TV sounds the same. A program without a theme is simply silent.
 */
class PromoDirector(private val context: Context, private val deps: PlayerDeps, private val scope: CoroutineScope) {
    data class Promo(
        val title: String,
        val subtitle: String?,
        val at: Instant,
        val summary: String?,
        val backdropUrl: String?,
        val later: List<Airing>,
    )

    private val _promo = MutableStateFlow<Promo?>(null)
    val promo: StateFlow<Promo?> = _promo.asStateFlow()

    private var theme: Media3PlayerEngine? = null
    private var job: Job? = null

    /** The gap this promo is for: the program that just ended and the one coming up. */
    private data class Gap(val airing: Airing, val next: Airing, val channel: net.clickarr.core.model.Channel)

    fun start(state: StateFlow<TuneState>, enabled: StateFlow<Boolean>) {
        scope.launch {
            combine(state, enabled) { s, on -> gapOf(s, on) }.distinctUntilChanged().collect { gap -> show(gap) }
        }
    }

    fun release() {
        job?.cancel()
        theme?.release()
        theme = null
    }

    private fun gapOf(s: TuneState, on: Boolean): Gap? {
        val airing = s.airing ?: return null
        val next = s.next ?: return null
        val channel = s.channel ?: return null
        return if (on && s.status == TuneStatus.Filler) Gap(airing, next, channel) else null
    }

    private fun show(gap: Gap?) {
        job?.cancel()
        theme?.stop()
        if (gap == null) {
            _promo.value = null
            return
        }
        // Text first, so the card is never empty while the artwork and synopsis load.
        _promo.value = Promo(gap.next.entry.title, gap.next.entry.subtitle, gap.next.start, null, null, emptyList())
        job = scope.launch {
            val provider = deps.provider(gap.next.entry.ref.provider)
            val item = (provider?.items(listOf(gap.next.entry.ref)) as? Outcome.Success)?.value?.firstOrNull()
            val later = laterTonight(gap)
            _promo.value = _promo.value?.copy(
                summary = item?.let(::summaryOf),
                backdropUrl = item?.artwork?.backdrop?.let { provider?.artworkUrl(it, ArtworkSize.BACKDROP) },
                later = later,
            )
            val themeUrl = item?.artwork?.theme?.let { provider?.themeUrl(it) } ?: return@launch
            val offset = (deps.clock.now() - gap.airing.contentEnd).coerceAtLeast(Duration.ZERO)
            if (offset < THEME_WINDOW) playTheme(themeUrl, offset)
        }
    }

    private suspend fun laterTonight(gap: Gap): List<Airing> {
        val lineup = deps.channels.lineup(gap.channel.lineup) ?: return emptyList()
        val out = ArrayList<Airing>()
        var last = gap.next
        while (out.size < LATER_COUNT) {
            last = deps.strategy.next(gap.channel, lineup, last) ?: break
            out += last
        }
        return out
    }

    private fun playTheme(url: String, offset: Duration) {
        val engine = theme ?: Media3PlayerEngine(context, deps.okHttp, "Clickarr").also { theme = it }
        engine.setVolume(THEME_VOLUME)
        engine.load(PlaybackSource.DirectPlay(url, emptyMap(), offset, mimeType = null))
        engine.play()
    }

    private fun summaryOf(item: MediaItem): String? = when (item) {
        is Episode -> item.summary
        is Movie -> item.summary
        is Show -> item.summary
        else -> null
    }

    private companion object {
        const val LATER_COUNT = 3
        const val THEME_VOLUME = 0.55f

        /** Themes run about a minute; tuning in later than this into a gap stays silent rather than starting mid-song. */
        val THEME_WINDOW = 2.minutes
    }
}
