package net.clickarr.household.protocol

import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.HouseholdState

/**
 * The coordinator's write path as a pure function: (state, command) -> new state with revision + 1,
 * or a typed failure. Validation lives here so the same rules apply to local edits and remote
 * commands, and so they can be tested without a server (proposal 14.2).
 */
class HouseholdReducer {

    fun apply(state: HouseholdState, command: Command, now: Instant): Outcome<HouseholdState> {
        val next = when (command) {
            is Command.CreateChannel -> createChannel(state, command)
            is Command.UpdateChannel -> updateChannel(state, command)
            is Command.DeleteChannel -> deleteChannel(state, command)
            is Command.SetFavorite -> setFavorite(state, command)
            is Command.RenameHousehold -> rename(state, command.name)
            is Command.RenameDevice -> renameDevice(state, command)
            is Command.RegisterServer -> Outcome.Success(
                state.copy(servers = state.servers.filter { it.serverIdentity != command.server.serverIdentity } + command.server),
            )
            is Command.RemoveDevice -> removeDevice(state, command)
        }
        return next.map { it.copy(revision = state.revision + 1) }.map { applyCutovers(it, now) }
    }

    /** Promote pending lineups whose time has come and drop snapshots nothing references. */
    fun applyCutovers(state: HouseholdState, now: Instant): HouseholdState {
        val channels = state.channels.map { ch ->
            val pending = ch.pendingLineup
            val at = ch.pendingAt
            val due = pending != null && at != null && at <= now
            if (due) ch.copy(lineup = pending!!, anchor = at!!, pendingLineup = null, pendingAt = null) else ch
        }
        val referenced = channels.flatMap { listOfNotNull(it.lineup, it.pendingLineup) }.toSet()
        return state.copy(channels = channels, lineups = state.lineups.filter { it.id in referenced })
    }

    private fun createChannel(state: HouseholdState, c: Command.CreateChannel): Outcome<HouseholdState> {
        val problem = firstProblem(
            "Channel ${c.channel.id.value} already exists" to state.channels.any { it.id == c.channel.id },
            "Channel number ${c.channel.number} is taken" to state.channels.any { it.number == c.channel.number },
            "Channel does not reference the supplied lineup" to (c.lineup.id != c.channel.lineup),
            "Lineup is empty" to c.lineup.entries.isEmpty(),
        )
        return problem?.let(::invalid)
            ?: Outcome.Success(state.copy(channels = state.channels + c.channel, lineups = state.lineups + c.lineup))
    }

    private fun updateChannel(state: HouseholdState, c: Command.UpdateChannel): Outcome<HouseholdState> {
        val existing = state.channels.firstOrNull { it.id == c.channel.id } ?: return notFound(c.channel.id.value)
        val lineups = if (c.lineup != null) state.lineups.filter { it.id != c.lineup.id } + c.lineup else state.lineups
        val known = lineups.map { it.id }.toSet()
        val problem = firstProblem(
            "Channel number ${c.channel.number} is taken" to state.channels.any { it.id != c.channel.id && it.number == c.channel.number },
            "Unknown lineup ${c.channel.lineup.value}" to (c.channel.lineup !in known),
            "Unknown pending lineup" to (c.channel.pendingLineup != null && c.channel.pendingLineup !in known),
        )
        if (problem != null) return invalid(problem)
        val channels = state.channels.map { if (it.id == existing.id) c.channel else it }
        return Outcome.Success(state.copy(channels = channels, lineups = lineups))
    }

    /** The message of the first failed check, or null when all pass. */
    private fun firstProblem(vararg checks: Pair<String, Boolean>): String? = checks.firstOrNull { it.second }?.first

    private fun deleteChannel(state: HouseholdState, c: Command.DeleteChannel): Outcome<HouseholdState> {
        if (state.channels.none { it.id == c.channelId }) return notFound(c.channelId.value)
        return Outcome.Success(
            state.copy(channels = state.channels.filter { it.id != c.channelId }, favorites = state.favorites - c.channelId),
        )
    }

    private fun setFavorite(state: HouseholdState, c: Command.SetFavorite): Outcome<HouseholdState> {
        if (state.channels.none { it.id == c.channelId }) return notFound(c.channelId.value)
        val favorites = if (c.favorite) state.favorites + c.channelId else state.favorites - c.channelId
        return Outcome.Success(state.copy(favorites = favorites))
    }

    private fun rename(state: HouseholdState, name: String): Outcome<HouseholdState> {
        if (name.isBlank()) return invalid("Household name cannot be blank")
        return Outcome.Success(state.copy(household = state.household.copy(name = name.trim())))
    }

    private fun renameDevice(state: HouseholdState, c: Command.RenameDevice): Outcome<HouseholdState> {
        if (state.devices.none { it.id == c.deviceId }) return notFound(c.deviceId.value)
        if (c.name.isBlank()) return invalid("Device name cannot be blank")
        return Outcome.Success(state.copy(devices = state.devices.map { if (it.id == c.deviceId) it.copy(name = c.name.trim()) else it }))
    }

    private fun removeDevice(state: HouseholdState, c: Command.RemoveDevice): Outcome<HouseholdState> {
        if (c.deviceId == state.household.coordinator) return invalid("The coordinator cannot remove itself")
        if (state.devices.none { it.id == c.deviceId }) return notFound(c.deviceId.value)
        return Outcome.Success(state.copy(devices = state.devices.filter { it.id != c.deviceId }))
    }

    private fun invalid(message: String) = Outcome.Failure(ClickarrError.Invalid(message))

    private fun notFound(id: String) = Outcome.Failure(ClickarrError.NotFound("No such item $id"))
}
