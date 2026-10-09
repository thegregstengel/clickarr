package net.clickarr.core.database

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.clickarr.core.model.Artwork
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.Episode
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.MediaVersion
import net.clickarr.core.model.Movie
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.Season
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.model.Show

/** Entity <-> model conversions. Pure functions so they can be unit tested without a database. */
object DbMappers {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val strings = ListSerializer(String.serializer())
    private val versions = ListSerializer(MediaVersion.serializer())

    // Provider connections

    fun ServerInfo.toEntity(altUrls: List<String> = emptyList(), lastOkAt: Instant? = null) = ProviderConnectionEntity(
        id = providerId.value, kind = kind.name, serverIdentity = serverIdentity, name = name, baseUrl = baseUrl,
        altUrls = json.encodeToString(strings, altUrls), version = version, lastOkAt = lastOkAt?.toEpochMilliseconds(),
    )

    fun ProviderConnectionEntity.toServerInfo() =
        ServerInfo(ProviderId(id), ProviderKind.valueOf(kind), serverIdentity, name, baseUrl, version)

    fun ProviderConnectionEntity.altUrlList(): List<String> = json.decodeFromString(strings, altUrls)

    // Libraries

    fun Library.toEntity() = LibraryEntity(ref.provider.value, ref.id.value, name, kind.name)

    fun LibraryEntity.toModel() = Library(MediaRef(ProviderId(providerId), NativeItemId(nativeId)), name, LibraryKind.valueOf(kind))

    // Media items

    fun MediaItem.toEntity(fetchedAt: Instant): MediaItemEntity {
        val base = MediaItemEntity(
            providerId = ref.provider.value, nativeId = ref.id.value, type = "", title = title, year = year,
            parentNativeId = null, grandparentNativeId = null, grandparentTitle = null, seasonIndex = null, episodeIndex = null,
            runtimeMs = null, genres = json.encodeToString(strings, genres), studio = null, network = null,
            artwork = json.encodeToString(Artwork.serializer(), artwork), versions = "[]", seasonCount = null, episodeCount = null,
            fetchedAt = fetchedAt.toEpochMilliseconds(),
        )
        return when (this) {
            is Show -> base.copy(
                type = TYPE_SHOW, studio = studio, network = network, seasonCount = seasonCount, episodeCount = episodeCount,
            )
            is Season -> base.copy(type = TYPE_SEASON, parentNativeId = show.id.value, seasonIndex = index)
            is Episode -> base.copy(
                type = TYPE_EPISODE, parentNativeId = season?.id?.value, grandparentNativeId = show.id.value,
                grandparentTitle = showTitle,
                seasonIndex = seasonIndex, episodeIndex = episodeIndex, runtimeMs = runtime.inWholeMilliseconds,
                versions = json.encodeToString(versions, media),
            )
            is Movie -> base.copy(
                type = TYPE_MOVIE, studio = studio, runtimeMs = runtime.inWholeMilliseconds, versions = json.encodeToString(versions, media),
            )
        }
    }

    fun MediaItemEntity.toModel(): MediaItem? {
        val provider = ProviderId(providerId)
        val ref = MediaRef(provider, NativeItemId(nativeId))
        val art = json.decodeFromString(Artwork.serializer(), artwork)
        val genreList = json.decodeFromString(strings, genres)
        return when (type) {
            TYPE_SHOW -> Show(ref, title, art, genreList, year, studio, network, seasonCount ?: 0, episodeCount ?: 0)
            TYPE_SEASON -> Season(
                ref, title, art, genreList, year, MediaRef(provider, NativeItemId(parentNativeId ?: "")), seasonIndex ?: 0,
            )
            TYPE_EPISODE -> Episode(
                ref, title, art, genreList, year,
                show = MediaRef(provider, NativeItemId(grandparentNativeId ?: "")), showTitle = grandparentTitle ?: "",
                season = parentNativeId?.let { MediaRef(provider, NativeItemId(it)) },
                seasonIndex = seasonIndex ?: 0, episodeIndex = episodeIndex ?: 0,
                runtime = (runtimeMs ?: 0L).milliseconds, media = json.decodeFromString(versions, this.versions),
            )
            TYPE_MOVIE -> Movie(
                ref, title, art, genreList, year, studio, (runtimeMs ?: 0L).milliseconds, json.decodeFromString(versions, this.versions),
            )
            else -> null
        }
    }

    // Channels

    fun Channel.toEntity(updatedAt: Instant) = ChannelEntity(
        id = id.value, number = number, name = name,
        icon = icon?.let { json.encodeToString(ChannelIcon.serializer(), it) },
        source = json.encodeToString(ProgrammingSource.serializer(), source),
        orderMode = order.name, slotRoundingMs = slotRounding?.inWholeMilliseconds, seed = seed,
        lineupId = lineup.value, anchorEpochMs = anchor.toEpochMilliseconds(),
        pendingLineupId = pendingLineup?.value, pendingAtEpochMs = pendingAt?.toEpochMilliseconds(),
        enabled = enabled, updatedAt = updatedAt.toEpochMilliseconds(),
    )

    fun ChannelEntity.toModel() = Channel(
        id = ChannelId(id), number = number, name = name,
        icon = icon?.let { json.decodeFromString(ChannelIcon.serializer(), it) },
        source = json.decodeFromString(ProgrammingSource.serializer(), source),
        order = OrderingMode.valueOf(orderMode), slotRounding = slotRoundingMs?.milliseconds, seed = seed,
        lineup = LineupSnapshotId(lineupId), anchor = Instant.fromEpochMilliseconds(anchorEpochMs),
        pendingLineup = pendingLineupId?.let(::LineupSnapshotId), pendingAt = pendingAtEpochMs?.let(Instant::fromEpochMilliseconds),
        enabled = enabled,
    )

    // Lineups

    fun LineupSnapshot.toEntities(): Pair<LineupSnapshotEntity, List<LineupEntryEntity>> =
        LineupSnapshotEntity(id.value, channelId.value, createdAt.toEpochMilliseconds(), contentHash, entries.size) to
            entries.mapIndexed { i, e ->
                LineupEntryEntity(id.value, i, e.ref.provider.value, e.ref.id.value, e.duration.inWholeMilliseconds, e.title, e.subtitle)
            }

    fun toLineup(snapshot: LineupSnapshotEntity, entries: List<LineupEntryEntity>) = LineupSnapshot(
        id = LineupSnapshotId(snapshot.id), channelId = ChannelId(snapshot.channelId),
        createdAt = Instant.fromEpochMilliseconds(snapshot.createdAtEpochMs),
        entries = entries.sortedBy { it.position }.map {
            LineupEntry(MediaRef(ProviderId(it.providerId), NativeItemId(it.nativeId)), it.durationMs.milliseconds, it.title, it.subtitle)
        },
        contentHash = snapshot.contentHash,
    )

    const val TYPE_SHOW = "SHOW"
    const val TYPE_SEASON = "SEASON"
    const val TYPE_EPISODE = "EPISODE"
    const val TYPE_MOVIE = "MOVIE"
}
