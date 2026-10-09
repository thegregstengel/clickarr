package net.clickarr.core.database

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId

fun Channel.toEntity(updatedAt: Instant) = ChannelEntity(
    id = id.value,
    number = number,
    name = name,
    icon = icon?.let { DbJson.json.encodeToString(ChannelIcon.serializer(), it) },
    source = DbJson.json.encodeToString(ProgrammingSource.serializer(), source),
    orderMode = order.name,
    slotRoundingMs = slotRounding?.inWholeMilliseconds,
    seed = seed,
    lineupId = lineup.value,
    anchorEpochMs = anchor.toEpochMilliseconds(),
    pendingLineupId = pendingLineup?.value,
    pendingAtEpochMs = pendingAt?.toEpochMilliseconds(),
    enabled = enabled,
    updatedAt = updatedAt.toEpochMilliseconds(),
)

fun ChannelEntity.toModel() = Channel(
    id = ChannelId(id),
    number = number,
    name = name,
    icon = icon?.let { DbJson.json.decodeFromString(ChannelIcon.serializer(), it) },
    source = DbJson.json.decodeFromString(ProgrammingSource.serializer(), source),
    order = OrderingMode.valueOf(orderMode),
    slotRounding = slotRoundingMs?.milliseconds,
    seed = seed,
    lineup = LineupSnapshotId(lineupId),
    anchor = Instant.fromEpochMilliseconds(anchorEpochMs),
    pendingLineup = pendingLineupId?.let(::LineupSnapshotId),
    pendingAt = pendingAtEpochMs?.let(Instant::fromEpochMilliseconds),
    enabled = enabled,
)

fun LineupSnapshot.toEntities(): Pair<LineupSnapshotEntity, List<LineupEntryEntity>> =
    LineupSnapshotEntity(id.value, channelId.value, createdAt.toEpochMilliseconds(), contentHash, entries.size) to
        entries.mapIndexed { i, e ->
            LineupEntryEntity(id.value, i, e.ref.provider.value, e.ref.id.value, e.duration.inWholeMilliseconds, e.title, e.subtitle)
        }

fun toLineup(snapshot: LineupSnapshotEntity, entries: List<LineupEntryEntity>) = LineupSnapshot(
    id = LineupSnapshotId(snapshot.id),
    channelId = ChannelId(snapshot.channelId),
    createdAt = Instant.fromEpochMilliseconds(snapshot.createdAtEpochMs),
    entries = entries.sortedBy { it.position }.map {
        LineupEntry(MediaRef(ProviderId(it.providerId), NativeItemId(it.nativeId)), it.durationMs.milliseconds, it.title, it.subtitle)
    },
    contentHash = snapshot.contentHash,
)
