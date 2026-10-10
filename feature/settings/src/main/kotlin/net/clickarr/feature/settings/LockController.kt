package net.clickarr.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.data.DevicePrefs
import net.clickarr.data.SettingsLock

/**
 * The settings lock from the pane's side: the gate that asks for the code when Settings opens, and the
 * General pane's set/confirm/remove flow. Owned by [SettingsViewModel]; "unlocked" lasts for one visit,
 * because [relock] runs whenever the settings screen leaves the composition.
 */
class LockController(private val prefs: DevicePrefs, private val scope: CoroutineScope, private val nowMs: () -> Long) {
    /** Where the General pane's set-a-code flow stands. */
    sealed interface Setup {
        data object Idle : Setup
        data object Enter : Setup
        data class Confirm(val first: String) : Setup
    }

    private val unlocked = MutableStateFlow(false)
    private val attempts = SettingsLock.Attempts()

    private val _entry = MutableStateFlow("")
    val entry: StateFlow<String> = _entry.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _setup = MutableStateFlow<Setup>(Setup.Idle)
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    val hasCode: StateFlow<Boolean> =
        prefs.settingsLock.combine(unlocked) { record, _ -> record != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** True while the gate should cover Settings. Starts false until the stored record is read, so no flash. */
    val locked: StateFlow<Boolean> =
        prefs.settingsLock.combine(unlocked) { record, open -> record != null && !open }
            .stateIn(scope, SharingStarted.Eagerly, false)

    fun digit(d: Int) {
        val wait = attempts.waitMs(nowMs())
        if (wait > 0 && _setup.value == Setup.Idle) {
            _message.value = "Too many tries. Wait ${(wait + MS_PER_S - 1) / MS_PER_S} seconds."
            return
        }
        val next = _entry.value + d
        _entry.value = next
        if (next.length < SettingsLock.LENGTH) return
        _entry.value = ""
        scope.launch { submit(next) }
    }

    fun clear() {
        _entry.value = ""
        _message.value = null
    }

    fun relock() {
        unlocked.value = false
        clear()
        _setup.value = Setup.Idle
    }

    fun beginSetup() {
        clear()
        _setup.value = Setup.Enter
    }

    fun cancelSetup() {
        clear()
        _setup.value = Setup.Idle
    }

    fun removeCode() = scope.launch {
        prefs.setSettingsLock(null)
        unlocked.value = false
        _message.value = "Code removed. Settings open freely again."
    }

    private suspend fun submit(code: String) {
        when (val s = _setup.value) {
            Setup.Idle -> unlock(code)
            Setup.Enter -> {
                _setup.value = Setup.Confirm(code)
                _message.value = "Enter it once more."
            }
            is Setup.Confirm -> if (code == s.first) {
                prefs.setSettingsLock(SettingsLock.record(code))
                unlocked.value = true
                _setup.value = Setup.Idle
                _message.value = "Code set. Settings will ask for it from now on."
            } else {
                _setup.value = Setup.Enter
                _message.value = "The two codes did not match. Start again."
            }
        }
    }

    private suspend fun unlock(code: String) {
        val record = prefs.settingsLock.first() ?: return
        if (SettingsLock.matches(code, record)) {
            attempts.succeeded()
            unlocked.value = true
            _message.value = null
        } else {
            attempts.failed(nowMs())
            val wait = attempts.waitMs(nowMs())
            _message.value = if (wait > 0) "Too many tries. Wait ${wait / MS_PER_S} seconds." else "Wrong code."
        }
    }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
