package net.clickarr.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun setDeviceName(name: String) = store.edit { it[DEVICE_NAME] = name }

    val lastChannelId: Flow<String?> = store.data.map { it[LAST_CHANNEL] }

    suspend fun setLastChannelId(id: String?) = store.edit { if (id == null) it.remove(LAST_CHANNEL) else it[LAST_CHANNEL] = id }

    val overlayTimeoutMs: Flow<Int> = store.data.map { it[OVERLAY_TIMEOUT] ?: DEFAULT_OVERLAY_TIMEOUT_MS }

    suspend fun setOverlayTimeoutMs(ms: Int) = store.edit { it[OVERLAY_TIMEOUT] = ms.coerceIn(2_000, 10_000) }

    companion object {
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val OVERLAY_TIMEOUT = intPreferencesKey("overlay_timeout_ms")
        const val DEFAULT_OVERLAY_TIMEOUT_MS = 5_000

        fun defaultDeviceName(): String = Build.MODEL.ifBlank { "Clickarr TV" }
    }
}
