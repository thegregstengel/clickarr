package net.clickarr.core.model

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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
    /** A holiday channel is in the guide and surfing only in that holiday's window each year; null means all year. */
    val holiday: Holiday? = null,
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
 * Holidays a channel can belong to (Settings, Channels, Holiday channels). Each has a window that is worked out per
 * year, ending on the holiday itself: the week before Valentine's Day and Easter, and from the first of the month
 * for Halloween, Thanksgiving (US, the fourth Thursday of November), and Christmas. Windows never overlap. The
 * channel's schedule runs all year so every TV agrees when it returns; the window only decides whether it is shown.
 */
@Serializable
enum class Holiday(val label: String) {
    VALENTINES("Valentine's Day"),
    EASTER("Easter"),
    HALLOWEEN("Halloween"),
    THANKSGIVING("Thanksgiving"),
    CHRISTMAS("Christmas"),
    ;

    /** The days this holiday's channel is on, in [year]. */
    fun window(year: Int): ClosedRange<LocalDate> = when (this) {
        VALENTINES -> LocalDate(year, Month.FEBRUARY, VALENTINE_FIRST)..LocalDate(year, Month.FEBRUARY, VALENTINE_DAY)
        EASTER -> easter(year).let { it.minus(WEEK, DateTimeUnit.DAY)..it }
        HALLOWEEN -> LocalDate(year, Month.OCTOBER, 1)..LocalDate(year, Month.OCTOBER, HALLOWEEN_DAY)
        THANKSGIVING -> LocalDate(year, Month.NOVEMBER, 1)..thanksgiving(year)
        CHRISTMAS -> LocalDate(year, Month.DECEMBER, 1)..LocalDate(year, Month.DECEMBER, CHRISTMAS_DAY)
    }

    fun contains(date: LocalDate): Boolean = date in window(date.year)

    companion object {
        private const val VALENTINE_FIRST = 7
        private const val VALENTINE_DAY = 14
        private const val HALLOWEEN_DAY = 31
        private const val CHRISTMAS_DAY = 25
        private const val WEEK = 7

        /** The fourth Thursday of November. */
        fun thanksgiving(year: Int): LocalDate {
            val first = LocalDate(year, Month.NOVEMBER, 1)
            val toThursday = (DayOfWeek.THURSDAY.isoDayNumber - first.dayOfWeek.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK
            return first.plus(toThursday + 3 * DAYS_IN_WEEK, DateTimeUnit.DAY)
        }

        /** Western (Gregorian) Easter Sunday, by the anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
        @Suppress("MagicNumber")
        fun easter(year: Int): LocalDate {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = (h + l - 7 * m + 114) % 31 + 1
            return LocalDate(year, month, day)
        }

        private const val DAYS_IN_WEEK = 7
    }
}

/** Whether [channel] is shown on [date]: always when it is not a holiday channel. */
fun Channel.inSeason(date: LocalDate): Boolean = holiday?.contains(date) ?: true

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
