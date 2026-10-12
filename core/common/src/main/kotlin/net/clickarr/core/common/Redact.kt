package net.clickarr.core.common

/**
 * Removes credentials from strings before they reach logs or diagnostics exports (proposal 16.3).
 * Covers Plex query tokens, generic api_key parameters, Authorization-style headers, and the Token field of
 * Jellyfin's `MediaBrowser` authorization.
 */
object Redact {
    private val queryParams = Regex("(?i)([?&](?:X-Plex-Token|api_key|ApiKey|token)=)[^&\\s]+")
    private val headerValues = Regex(
        "(?i)((?:Authorization|X-Plex-Token|X-Api-Key)\\s*:\\s*(?:Bearer\\s+)?)([^\\s,;]+)",
    )
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+")
    private val mediaBrowserToken = Regex("(?i)(\\bToken=\")[^\"]*")

    fun apply(input: String): String =
        input
            .replace(queryParams) { "${it.groupValues[1]}<redacted>" }
            .replace(headerValues) { "${it.groupValues[1]}<redacted>" }
            .replace(bearer) { "${it.groupValues[1]}<redacted>" }
            .replace(mediaBrowserToken) { "${it.groupValues[1]}<redacted>" }
}
