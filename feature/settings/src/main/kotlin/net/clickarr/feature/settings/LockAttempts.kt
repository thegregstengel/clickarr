package net.clickarr.feature.settings

import net.clickarr.data.SettingsLock

/** One wrong-guess counter for the whole process; a new settings screen must not start with a clean slate. */
internal object LockAttempts {
    val shared = SettingsLock.Attempts()
}
