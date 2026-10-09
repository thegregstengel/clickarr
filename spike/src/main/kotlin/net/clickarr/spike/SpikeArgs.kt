package net.clickarr.spike

/**
 * Launch parameters, because typing a URL with a TV remote is miserable. Example:
 *
 * adb shell am start -n net.clickarr.debug/net.clickarr.MainActivity \
 *   --es spike player --es url "http://plex.local:32400/library/parts/123/file.mkv?X-Plex-Token=..." --ei offsetSec 1020
 */
data class SpikeArgs(
    val spike: String? = null,
    val url: String? = null,
    val offsetSec: Int = 0,
)
