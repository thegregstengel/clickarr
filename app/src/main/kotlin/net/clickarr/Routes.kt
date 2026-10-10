package net.clickarr

object Routes {
    const val SETUP = "setup"
    const val PLAYER = "player"
    const val SHELL = "shell?tab={tab}&section={section}"
    const val EDITOR = "editor?channel={channel}"
    const val SUGGEST = "suggest"

    /** The shell on [tab] (guide, favorites, settings); [section] opens Settings on that pane. */
    fun shell(tab: String = "guide", section: String? = null) =
        if (section == null) "shell?tab=$tab" else "shell?tab=$tab&section=$section"

    fun editor(channelId: String? = null) = if (channelId == null) "editor" else "editor?channel=$channelId"
}
