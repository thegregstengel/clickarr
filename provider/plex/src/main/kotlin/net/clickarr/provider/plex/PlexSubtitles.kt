package net.clickarr.provider.plex

/** Which of an item's subtitle tracks to show, and how the player can read the ones that are separate files. */
internal object PlexSubtitles {
    private const val SUBTITLE = 3

    fun of(part: PlexPart?): List<PlexStream> = part?.streams.orEmpty().filter { it.streamType == SUBTITLE }

    /**
     * The track to show when captions are on: the one already chosen in Plex, else full (not forced-only) subtitles
     * in the viewer's language, else any track in that language, else the first full track, else the first.
     */
    fun choose(streams: List<PlexStream>, language: String?): PlexStream? {
        val inLanguage = streams.filter { language != null && it.languageCode.equals(language, ignoreCase = true) }
        return streams.firstOrNull { it.selected }
            ?: inLanguage.firstOrNull { !it.forced }
            ?: inLanguage.firstOrNull()
            ?: streams.firstOrNull { !it.forced }
            ?: streams.firstOrNull()
    }

    /** MIME type for a text subtitle file the player can parse, or null for image formats it cannot load as a file. */
    fun mimeType(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt", "webvtt" -> "text/vtt"
        "ttml" -> "application/ttml+xml"
        else -> null
    }
}
