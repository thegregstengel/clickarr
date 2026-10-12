package net.clickarr.core.model

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrderingMode { SEQUENTIAL, SHUFFLE }

@Serializable
enum class EpisodeOrder { AIRED, INTERLEAVED }

@Serializable
sealed interface ChannelIcon {
    /** One of the built-in glyph names (Lucide set). */
    @Serializable
    @SerialName("glyph")
    data class Glyph(val name: String) : ChannelIcon

    @Serializable
    @SerialName("art")
    data class Art(val ref: ArtworkRef) : ChannelIcon
}

/**
 * Narrows a library to the media a channel wants. All sets are OR within a field and AND across fields:
 * genres {Comedy} + decade 1980 means "comedies from the 1980s". Empty means "no restriction".
 * Examples: a retro channel is `MediaFilter(genres = setOf("Comedy"), decadeStart = 1980)`.
 */
@Serializable
data class MediaFilter(
    val genres: Set<String> = emptySet(),
    /** First year of the decade, e.g. 1980 for the 1980s. */
    val decadeStart: Int? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val networks: Set<String> = emptySet(),
    val studios: Set<String> = emptySet(),
    /** Plex labels. */
    val labels: Set<String> = emptySet(),
    val contentRatings: Set<String> = emptySet(),
)

/** What media is eligible for a channel. Resolved by a provider into a [LineupSnapshot]. */
@Serializable
sealed interface ProgrammingSource {
    @Serializable
    @SerialName("shows")
    data class Shows(val shows: List<MediaRef>, val episodeOrder: EpisodeOrder = EpisodeOrder.AIRED) : ProgrammingSource

    @Serializable
    @SerialName("library")
    data class Library(val library: MediaRef, val filter: MediaFilter = MediaFilter()) : ProgrammingSource

    @Serializable
    @SerialName("collection")
    data class Collection(val ref: MediaRef) : ProgrammingSource

    @Serializable
    @SerialName("playlist")
    data class Playlist(val ref: MediaRef) : ProgrammingSource

    /** Hand-picked items in the order given. A Lord of the Rings channel is just the films, in order. */
    @Serializable
    @SerialName("explicit")
    data class Explicit(val items: List<MediaRef>) : ProgrammingSource

    /**
     * Several sources combined into one channel, in the order listed, duplicates removed by [MediaRef].
     * Lets a channel be "these two collections plus these three shows".
     */
    @Serializable
    @SerialName("union")
    data class Union(val sources: List<ProgrammingSource>) : ProgrammingSource
}

@Serializable
data class Channel(
    val id: ChannelId,
    val number: Int,
    val name: String,
    val icon: ChannelIcon? = null,
    val source: ProgrammingSource,
    val order: OrderingMode = OrderingMode.SEQUENTIAL,
    /** Pad each program to a multiple of this; null means no padding. */
    val slotRounding: Duration? = null,
    /** Fixed at creation. Drives shuffle permutations. */
    val seed: Long,
    /** Episodes of one show in a row, or null for one at a time (scheduler version 2). */
    val runs: EpisodeRuns? = null,
    /** Shown in the guide and when surfing only between these dates each year; null means all year. */
    val season: SeasonWindow? = null,
    /** The lineup currently in effect. */
    val lineup: LineupSnapshotId,
    /** Cycle 0 of [lineup] begins here. */
    val anchor: Instant,
    /** A lineup waiting to take effect at [pendingAt] (proposal 9.6). */
    val pendingLineup: LineupSnapshotId? = null,
    val pendingAt: Instant? = null,
    val enabled: Boolean = true,
)

@Serializable
data class LineupEntry(
    val ref: MediaRef,
    val duration: Duration,
    val title: String,
    val subtitle: String? = null,
    /** Entries that belong together in order (a show's episodes): the key is the show's id. Movies have none. */
    val group: String? = null,
)

/**
 * A yearly window, such as October for a Halloween channel or late November to New Year for Christmas movies.
 * Inclusive at both ends; a window that crosses the new year (start after end) wraps. The channel's schedule keeps
 * running all year so every TV agrees when it reappears; the window only decides whether it is shown.
 */
@Serializable
data class SeasonWindow(val startMonth: Int, val startDay: Int, val endMonth: Int, val endDay: Int) {
    init {
        require(startMonth in 1..12 && endMonth in 1..12 && startDay in 1..31 && endDay in 1..31) { "invalid season window" }
    }

    fun contains(date: LocalDate): Boolean {
        val d = key(date.monthNumber, date.dayOfMonth)
        val s = key(startMonth, startDay)
        val e = key(endMonth, endDay)
        return if (s <= e) d in s..e else d >= s || d <= e
    }

    /** "10-01..10-31", the stored form. */
    fun encode(): String = "%02d-%02d..%02d-%02d".format(startMonth, startDay, endMonth, endDay)

    companion object {
        private const val MONTH = 100
        private fun key(month: Int, day: Int) = month * MONTH + day

        val HALLOWEEN = SeasonWindow(10, 1, 10, 31)
        val CHRISTMAS = SeasonWindow(11, 25, 12, 31)
        val SUMMER = SeasonWindow(6, 1, 8, 31)

        fun decode(s: String?): SeasonWindow? = s?.let {
            val m = Regex("""(\d{2})-(\d{2})\.\.(\d{2})-(\d{2})""").matchEntire(it) ?: return null
            val (a, b, c, d) = m.destructured
            runCatching { SeasonWindow(a.toInt(), b.toInt(), c.toInt(), d.toInt()) }.getOrNull()
        }
    }
}

/** Whether [channel] is shown on [date]: always when it has no season. */
fun Channel.inSeason(date: LocalDate): Boolean = season?.contains(date) ?: true

/**
 * How many entries of one group play back to back before the channel moves on: between [min] and [max],
 * chosen per run from the channel's seed. With shuffle the runs are shuffled; in order they alternate
 * between groups. Episodes inside a run always stay in aired order.
 */
@Serializable
data class EpisodeRuns(val min: Int, val max: Int) {
    init {
        require(min >= 1 && max >= min) { "runs need 1 <= min <= max" }
    }
}

/**
 * The frozen, ordered list of items a channel cycles through. Two devices holding the same
 * snapshot (same [contentHash]) compute the same schedule. See ADR 0008.
 */
@Serializable
data class LineupSnapshot(
    val id: LineupSnapshotId,
    val channelId: ChannelId,
    val createdAt: Instant,
    val entries: List<LineupEntry>,
    val contentHash: String,
)

/** A computed program slot. Never persisted. */
data class Airing(
    val channelId: ChannelId,
    val entry: LineupEntry,
    val start: Instant,
    /** End of the padded slot. Filler plays between [contentEnd] and [end]. */
    val end: Instant,
    val contentEnd: Instant,
    val cycle: Long,
    val indexInCycle: Int,
)
