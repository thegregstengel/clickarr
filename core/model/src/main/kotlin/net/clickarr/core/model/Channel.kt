package net.clickarr.core.model

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class OrderingMode { SEQUENTIAL, SHUFFLE }

@Serializable
enum class EpisodeOrder { AIRED, INTERLEAVED }

@Serializable
sealed interface ChannelIcon {
    /** One of the built-in glyph names (Lucide set). */
    @Serializable
    data class Glyph(val name: String) : ChannelIcon

    @Serializable
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
    /** Plex labels (Jellyfin/Emby tags later). */
    val labels: Set<String> = emptySet(),
    val contentRatings: Set<String> = emptySet(),
)

/** What media is eligible for a channel. Resolved by a provider into a [LineupSnapshot]. */
@Serializable
sealed interface ProgrammingSource {
    @Serializable
    data class Shows(val shows: List<MediaRef>, val episodeOrder: EpisodeOrder = EpisodeOrder.AIRED) : ProgrammingSource

    @Serializable
    data class Library(val library: MediaRef, val filter: MediaFilter = MediaFilter()) : ProgrammingSource

    @Serializable
    data class Collection(val ref: MediaRef) : ProgrammingSource

    @Serializable
    data class Playlist(val ref: MediaRef) : ProgrammingSource

    /** Hand-picked items in the order given. A Lord of the Rings channel is just the films, in order. */
    @Serializable
    data class Explicit(val items: List<MediaRef>) : ProgrammingSource

    /**
     * Several sources combined into one channel, in the order listed, duplicates removed by [MediaRef].
     * Lets a channel be "these two collections plus these three shows".
     */
    @Serializable
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
)

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
