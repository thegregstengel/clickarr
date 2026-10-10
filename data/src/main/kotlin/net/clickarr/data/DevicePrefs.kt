package net.clickarr.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore("device_prefs")

/**
 * Device-local state that is never synchronized (proposal 16.1): identity, last channel, UI knobs.
 * Preferences DataStore rather than Proto for now; the key set is tiny.
 */
class DevicePrefs(context: Context) {
    private val store = context.applicationContext.deviceStore

    /** Stable per install. Read once at startup, hence the single blocking read. */
    val deviceId: String by lazy {
        runBlocking {
            store.data.first()[DEVICE_ID] ?: UUID.randomUUID().toString().also { id -> store.edit { it[DEVICE_ID] = id } }
        }
    }

    val deviceName: Flow<String> = store.data.map { it[DEVICE_NAME] ?: defaultDeviceName() }

    suspend fun deviceNameNow(): String = deviceName.first()

    suspend fun setDeviceName(name: String) {
        store.edit { it[DEVICE_NAME] = name }
    }

    val lastChannelId: Flow<String?> = store.data.map { it[LAST_CHANNEL] }

    suspend fun setLastChannelId(id: String?) {
        store.edit { if (id == null) it.remove(LAST_CHANNEL) else it[LAST_CHANNEL] = id }
    }

    val overlayTimeoutMs: Flow<Int> = store.data.map { it[OVERLAY_TIMEOUT] ?: DEFAULT_OVERLAY_TIMEOUT_MS }

    suspend fun setOverlayTimeoutMs(ms: Int) {
        store.edit { it[OVERLAY_TIMEOUT] = ms.coerceIn(2_000, 10_000) }
    }

    /** Multiplies the display density for the whole UI (Settings, Appearance, Size). 1.0 is the design's 1080p scale. */
    val uiScale: Flow<Float> = store.data.map { it[UI_SCALE] ?: DEFAULT_UI_SCALE }

    suspend fun setUiScale(scale: Float) {
        store.edit { it[UI_SCALE] = scale.coerceIn(MIN_UI_SCALE, 1f) }
    }

    /** Which builds the updater offers: nightly or release (Settings, About and Updates). */
    val updateChannel: Flow<String> = store.data.map { it[UPDATE_CHANNEL] ?: DEFAULT_UPDATE_CHANNEL }

    suspend fun setUpdateChannel(channel: String) {
        store.edit { it[UPDATE_CHANNEL] = channel }
    }

    /** How far ahead the guide reaches, in hours (Settings, Channels). */
    val guideHours: Flow<Int> = store.data.map { it[GUIDE_HOURS] ?: DEFAULT_GUIDE_HOURS }

    suspend fun setGuideHours(hours: Int) {
        store.edit { it[GUIDE_HOURS] = hours.coerceIn(MIN_GUIDE_HOURS, MAX_GUIDE_HOURS) }
    }

    /** Palette name for Settings, General, Theme: default, dark, light, or dracula. */
    val theme: Flow<String> = store.data.map { it[THEME] ?: DEFAULT_THEME }

    suspend fun setTheme(name: String) {
        store.edit { it[THEME] = name }
    }

    companion object {
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val OVERLAY_TIMEOUT = intPreferencesKey("overlay_timeout_ms")
        const val DEFAULT_OVERLAY_TIMEOUT_MS = 5_000
        private val UI_SCALE = floatPreferencesKey("ui_scale")
        const val DEFAULT_UI_SCALE = 0.65f
        const val MIN_UI_SCALE = 0.5f
        private val THEME = stringPreferencesKey("theme")
        private val GUIDE_HOURS = intPreferencesKey("guide_hours")
        private val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        const val DEFAULT_UPDATE_CHANNEL = "nightly"
        const val DEFAULT_GUIDE_HOURS = 6
        const val MIN_GUIDE_HOURS = 3
        const val MAX_GUIDE_HOURS = 24
        const val DEFAULT_THEME = "default"

        fun defaultDeviceName(): String = Build.MODEL.ifBlank { "Clickarr TV" }
    }
}
