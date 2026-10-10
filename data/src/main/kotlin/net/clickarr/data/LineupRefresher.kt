package net.clickarr.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome

/**
 * Keeps lineups current without anyone pressing Refresh (Settings, Channels, "Keep lineups current").
 * Once a day, while the app is running, every channel's source is re-read from Plex; a channel whose
 * content changed gets a pending lineup that cuts over at its next program boundary, the same path the
 * manual button uses. A household member leaves this to the coordinator so two TVs never race.
 */
@Singleton
class LineupRefresher @Inject constructor(
    private val prefs: DevicePrefs,
    private val channels: ChannelRepository,
    private val registry: ProviderRegistry,
    private val household: HouseholdService,
    private val clock: Clock,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                runCatching { if (shouldRunNow()) runOnce() }.onFailure { Log.w(TAG, it) { "automatic refresh failed" } }
                delay(CHECK_EVERY)
            }
        }
    }

    private suspend fun shouldRunNow(): Boolean {
        if (!prefs.autoRefresh.first()) return false
        if (registry.primary == null) return false
        if (household.role.value is HouseholdService.Role.Member) return false
        return isDue(prefs.lastAutoRefreshAt.first(), clock.now().toEpochMilliseconds())
    }

    /** Re-resolves every channel and records what changed; the caller decides when. */
    suspend fun runOnce(): String {
        val all = channels.all()
        var changed = 0
        for (before in all) {
            val after = (channels.refreshLineup(before.id, applyNow = false) as? Outcome.Success)?.value ?: continue
            if (after.lineup != before.lineup || after.pendingLineup != before.pendingLineup) changed++
        }
        val note = summary(changed, all.size)
        prefs.recordAutoRefresh(clock.now().toEpochMilliseconds(), note)
        Log.i(TAG) { "automatic refresh: $note" }
        return note
    }

    companion object {
        private const val TAG = "Refresh"
        private val CHECK_EVERY = 1.hours
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Due when never run, a day or more after the last run, or when the clock says the last run is in the future. */
        fun isDue(last: Long?, now: Long): Boolean = last == null || now - last >= INTERVAL_MS || last > now

        fun summary(changed: Int, total: Int): String {
            val channelsWord = if (total == 1) "channel" else "channels"
            return if (changed == 0) "no changes in $total $channelsWord" else "$changed of $total channels picked up changes"
        }
    }
}
