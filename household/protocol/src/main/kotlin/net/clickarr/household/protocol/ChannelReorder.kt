package net.clickarr.household.protocol

import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId

/**
 * Reassigns the numbers held by non-holiday channels so they follow [orderedIds].
 * Holiday channels keep their numbers and stay in the list unchanged. The set of regular-channel
 * numbers is preserved; only which regular channel holds which number changes.
 */
fun applyRegularChannelOrder(channels: List<Channel>, orderedIds: List<ChannelId>): Outcome<List<Channel>> {
    val regulars = channels.filter { it.holiday == null }
    val regularIds = regulars.map { it.id }.toSet()
    // Holiday channels are never in [orderedIds]; the set check also rejects unknown or duplicate ids.
    if (orderedIds.size != regulars.size || orderedIds.toSet() != regularIds) {
        return Outcome.Failure(ClickarrError.Invalid("Reorder must list every regular channel once"))
    }
    val numbers = regulars.map { it.number }.sorted()
    val byId = regulars.associateBy { it.id }
    val renumbered = orderedIds.mapIndexed { i, id -> byId.getValue(id).copy(number = numbers[i]) }.associateBy { it.id }
    return Outcome.Success(channels.map { ch -> renumbered[ch.id] ?: ch })
}
