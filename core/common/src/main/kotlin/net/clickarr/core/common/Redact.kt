package net.clickarr.core.common

/**
 * Removes credentials from strings before they reach logs or diagnostics exports (proposal 16.3).
 * Covers Plex query tokens, Jellyfin/Emby api_key parameters, and Authorization-style headers.
 */
object Redact {
    private val queryParams = Regex("(?i)([?&](?:X-Plex-Token|api_key|ApiKey|token)=)[^&\\s]+")
    private val headerValues = Regex("(?i)((?:Authorization|X-Emby-Token|X-MediaBrowser-Token|X-Plex-Token)\\s*[:=]\\s*)(\\S+)")
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+")

    fun apply(input: String): String =
        input
            .replace(queryParams) { "${it.groupValues[1]}<redacted>" }
            .replace(headerValues) { "${it.groupValues[1]}<redacted>" }
            .replace(bearer) { "${it.groupValues[1]}<redacted>" }
}
