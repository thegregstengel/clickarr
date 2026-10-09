package net.clickarr

object Routes {
    const val SETUP = "setup"
    const val PLAYER = "player"
    const val SHELL = "shell?tab={tab}"

    fun shell(tab: String = "channels") = "shell?tab=$tab"
    const val EDITOR = "editor"
}
