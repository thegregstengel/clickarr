package net.clickarr.feature.settings

import androidx.compose.ui.focus.FocusRequester

/** Focus hand-off between the shell cog and the Settings left rail. */
class SettingsFocus(
    /** Where Down from the shell's cog lands: the first section, or the lock pad while Settings is locked. */
    val entry: FocusRequester,
    /** Up from the first left-rail item; the shell's settings cog. Null keeps spatial search. */
    val exit: FocusRequester? = null,
    /** When true and unlocked, claim focus into the selected section (or Channels actions). */
    val take: Boolean = false,
)
