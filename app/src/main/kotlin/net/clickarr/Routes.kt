package net.clickarr

object Routes {
    const val SETUP = "setup"
    const val PLAYER = "player"
    const val SHELL = "shell?tab={tab}"

    fun shell(tab: String = "channels") = "shell?tab=$tab"
    const val EDITOR = "editor?channel={channel}"

    fun editor(channelId: String? = null) = if (channelId == null) "editor" else "editor?channel=$channelId"
}
