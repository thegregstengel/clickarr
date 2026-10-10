package net.clickarr.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.database.FavoriteEntity
import net.clickarr.core.database.toEntities
import net.clickarr.core.database.toEntity
import net.clickarr.core.database.toLineup
import net.clickarr.core.database.toModel
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.Episode
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.Playable
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.scheduling.Lineups
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.household.protocol.Command

/**
 * Channels and their lineup snapshots. Creating a channel resolves its programming source through the
 * provider once and freezes the result (ADR 0008); later refreshes cut over at a program boundary
 * (ADR 0010). In Phase 2 the household coordinator takes over the write side of this class.
 */
@Singleton
class ChannelRepository @Inject constructor(
    private val db: ClickarrDatabase,
    private val registry: ProviderRegistry,
    private val strategy: ScheduleStrategy,
    private val clock: Clock,
    private val household: HouseholdService,
) {
    val channels: Flow<List<Channel>> = db.channels().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun all(): List<Channel> = db.channels().all().map { it.toModel() }

    val favorites: Flow<Set<ChannelId>> = db.favorites().observeAll().map { ids -> ids.map(::ChannelId).toSet() }

    suspend fun setFavorite(id: ChannelId, favorite: Boolean) {
        if (routed(Command.SetFavorite(id, favorite))) return
        if (favorite) db.favorites().add(FavoriteEntity(id.value)) else db.favorites().remove(FavoriteEntity(id.value))
    }

    /** True when the household took the write (or refused it, in which case the error is logged). */
    private suspend fun routed(command: Command): Boolean = when (val r = household.apply(command)) {
        is Outcome.Success -> r.value
        is Outcome.Failure -> throw HouseholdWriteException(r.error)
    }

    /** Re-resolve every channel's source; changes cut over at each channel's next program boundary. */
    suspend fun refreshAll(): Int = all().count { refreshLineup(it.id, applyNow = false) is Outcome.Success }

    suspend fun byId(id: ChannelId): Channel? = db.channels().byId(id.value)?.toModel()

    suspend fun lineup(id: LineupSnapshotId): LineupSnapshot? {
        val snap = db.lineups().snapshot(id.value) ?: return null
        return toLineup(snap, db.lineups().entries(id.value))
    }

    suspend fun nextFreeNumber(): Int {
        val used = db.channels().all().map { it.number }.toSet()
        return generateSequence(2) { it + 1 }.first { it !in used }
    }

    suspend fun create(
        number: Int,
        name: String,
        source: ProgrammingSource,
        order: OrderingMode = OrderingMode.SEQUENTIAL,
        slotRounding: Duration? = 30.minutes,
        icon: ChannelIcon? = null,
    ): Outcome<Channel> {
        if (db.channels().byNumber(number) != null) return Outcome.Failure(ClickarrError.Invalid("Channel $number already exists"))
        return resolveEntries(source).flatMap { entries ->
            if (entries.isEmpty()) return@flatMap Outcome.Failure(ClickarrError.Invalid("Nothing to schedule for that source"))
            val channelId = ChannelId(UUID.randomUUID().toString())
            val now = clock.now()
            val snapshot = Lineups.create(LineupSnapshotId(UUID.randomUUID().toString()), channelId, entries, now)
            val channel = Channel(
                id = channelId, number = number, name = name, icon = icon, source = source, order = order,
                slotRounding = slotRounding, seed = Random.nextLong(), lineup = snapshot.id,
                anchor = alignedAnchor(now, slotRounding),
            )
            persist(channel, snapshot, now)
        }
    }

    /** Through the household when in one, otherwise straight into the database. */
    private suspend fun persist(channel: Channel, snapshot: LineupSnapshot, now: Instant): Outcome<Channel> =
        household.apply(Command.CreateChannel(channel, snapshot)).flatMap { handled ->
            if (!handled) {
                val (snapEntity, entryEntities) = snapshot.toEntities()
                db.lineups().insert(snapEntity, entryEntities)
                db.channels().upsert(channel.toEntity(now))
            }
            Outcome.Success(channel)
        }

    suspend fun update(channel: Channel) {
        if (routed(Command.UpdateChannel(channel))) return
        db.channels().upsert(channel.toEntity(clock.now()))
    }

    suspend fun delete(id: ChannelId) {
        if (routed(Command.DeleteChannel(id))) return
        db.channels().delete(id.value)
        db.lineups().orphanIds().forEach { db.lineups().delete(it) }
    }

    /**
     * Re-resolve the source. With [applyNow] the new lineup starts immediately at a fresh anchor;
     * otherwise it is queued to start when the current program ends, so every device switches together.
     */
    suspend fun refreshLineup(id: ChannelId, applyNow: Boolean): Outcome<Channel> {
        val channel = byId(id) ?: return Outcome.Failure(ClickarrError.NotFound("Channel not found"))
        return resolveEntries(channel.source).flatMap { entries ->
            val now = clock.now()
            val snapshot = Lineups.create(LineupSnapshotId(UUID.randomUUID().toString()), id, entries, now)
            val current = lineup(channel.lineup)
            if (current?.contentHash == snapshot.contentHash) return@flatMap Outcome.Success(channel)
            val cutover = if (applyNow || current == null) null else strategy.airingAt(channel, current, now)?.end
            val updated = if (cutover == null) {
                channel.copy(lineup = snapshot.id, anchor = alignedAnchor(now, channel.slotRounding), pendingLineup = null, pendingAt = null)
            } else {
                channel.copy(pendingLineup = snapshot.id, pendingAt = cutover)
            }
            household.apply(Command.UpdateChannel(updated, snapshot)).flatMap { handled ->
                if (!handled) {
                    val (snapEntity, entryEntities) = snapshot.toEntities()
                    db.lineups().insert(snapEntity, entryEntities)
                    db.channels().upsert(updated.toEntity(now))
                }
                Outcome.Success(updated)
            }
        }
    }

    /** Promote any pending lineup whose time has come. Cheap; called on each tune and on startup. */
    suspend fun applyPendingCutovers() {
        val now = clock.now()
        db.channels().all().map { it.toModel() }
            .filter { it.pendingLineup != null && it.pendingAt != null && it.pendingAt!! <= now }
            .forEach { ch ->
                val promoted = ch.copy(lineup = ch.pendingLineup!!, anchor = ch.pendingAt!!, pendingLineup = null, pendingAt = null)
                db.channels().upsert(promoted.toEntity(now))
            }
        db.lineups().orphanIds().forEach { db.lineups().delete(it) }
    }

    private suspend fun resolveEntries(source: ProgrammingSource): Outcome<List<LineupEntry>> {
        val providerId = source.providerId() ?: return Outcome.Failure(ClickarrError.Invalid("Source has no items"))
        val provider = registry.get(providerId) ?: return Outcome.Failure(ClickarrError.Unreachable("Server not connected"))
        return provider.resolve(source).map { playables -> playables.filter { it.runtime.isPositive() }.map { it.toEntry() } }
    }

    private fun Playable.toEntry() = when (this) {
        is Episode -> LineupEntry(ref, runtime, showTitle, "S${seasonIndex}E$episodeIndex $title")
        is Movie -> LineupEntry(ref, runtime, title, year?.toString())
    }

    /**
     * With slot rounding, start the schedule on a wall-clock boundary (7:00, 7:30) in the local time
     * zone so the guide columns line up with the clock. Without it, the anchor is simply now.
     */
    private fun alignedAnchor(now: Instant, rounding: Duration?): Instant {
        if (rounding == null || rounding <= Duration.ZERO) return now
        val tz = TimeZone.currentSystemDefault()
        val local = now.toLocalDateTime(tz)
        val minutesIntoDay = local.hour * 60 + local.minute
        val step = rounding.inWholeMinutes.toInt().coerceAtLeast(1)
        val floored = minutesIntoDay - minutesIntoDay % step
        val boundary = kotlinx.datetime.LocalDateTime(local.date, kotlinx.datetime.LocalTime(floored / 60, floored % 60))
        return boundary.toInstant(tz)
    }
}

/** The server a source lives on, taken from its first media reference. */
fun ProgrammingSource.providerId(): ProviderId? = firstRef()?.provider

private fun ProgrammingSource.firstRef(): MediaRef? = when (this) {
    is ProgrammingSource.Shows -> shows.firstOrNull()
    is ProgrammingSource.Library -> library
    is ProgrammingSource.Collection -> ref
    is ProgrammingSource.Playlist -> ref
    is ProgrammingSource.Explicit -> items.firstOrNull()
    is ProgrammingSource.Union -> sources.firstNotNullOfOrNull { it.firstRef() }
}

/** A household refused or could not take a write; the message is user-facing. */
class HouseholdWriteException(val error: ClickarrError) : RuntimeException(error.message)
