package net.clickarr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings, Playback preferred audio, plus the last language picked in the player panel.
 * Device-local, same store as [DevicePrefs].
 */
@Singleton
class AudioPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.applicationContext.deviceStore

    /**
     * Preferred audio: [AUDIO_DEVICE] (TV locale), [AUDIO_ORIGINAL] (server default), or an ISO language code.
     * Null in the store means device language.
     */
    val preferredLanguage: Flow<String?> = store.data.map { it[AUDIO_LANG] }

    suspend fun setPreferredLanguage(code: String?) {
        store.edit { if (code == null) it.remove(AUDIO_LANG) else it[AUDIO_LANG] = code }
    }

    /** Last language chosen in the player Audio & video panel; soft default for the next tune. */
    val lastLanguage: Flow<String?> = store.data.map { it[LAST_AUDIO_LANG] }

    suspend fun setLastLanguage(code: String?) {
        store.edit { if (code == null) it.remove(LAST_AUDIO_LANG) else it[LAST_AUDIO_LANG] = code }
    }

    companion object {
        private val AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        private val LAST_AUDIO_LANG = stringPreferencesKey("last_audio_language")
        const val AUDIO_DEVICE = "device"
        const val AUDIO_ORIGINAL = "original"
    }
}
