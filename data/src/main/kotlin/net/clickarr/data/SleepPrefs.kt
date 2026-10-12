package net.clickarr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings, Playback: the sleep timer and "Are you still watching?". Device-local, in the same store as
 * [DevicePrefs]. The timer is a moment on the app clock; the check is a number of hours without a key press.
 */
@Singleton
class SleepPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.applicationContext.deviceStore

    /** When playback stops, in app-clock epoch milliseconds, or null for no timer. Cleared once it fires. */
    val sleepAt: Flow<Long?> = store.data.map { it[SLEEP_AT] }

    suspend fun setSleepAt(epochMs: Long?) {
        store.edit { if (epochMs == null) it.remove(SLEEP_AT) else it[SLEEP_AT] = epochMs }
    }

    /** Hours without a key press before asking, or 0 for never. */
    val stillWatchingHours: Flow<Int> = store.data.map { it[STILL_WATCHING] ?: DEFAULT_STILL_WATCHING_HOURS }

    suspend fun setStillWatchingHours(hours: Int) {
        store.edit { it[STILL_WATCHING] = hours.coerceIn(0, MAX_HOURS) }
    }

    companion object {
        private val SLEEP_AT = longPreferencesKey("sleep_at")
        private val STILL_WATCHING = intPreferencesKey("still_watching_hours")
        const val DEFAULT_STILL_WATCHING_HOURS = 4
        private const val MAX_HOURS = 12
    }
}
