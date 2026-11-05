package net.clickarr.feature.channels

import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.data.ChannelRepository

/** Edit-Selections / Save helpers kept out of [EditorViewModel] for detekt function limits. */
internal object EditorSelectionsOps {
    /** Persist metadata (and source); rebuild the lineup when the source changed. */
    suspend fun persistEdit(
        repository: ChannelRepository,
        channel: Channel,
        draft: EditorDraft,
        name: String,
        sourceChanged: Boolean,
    ): Outcome<Channel> {
        val updated = channel.copy(
            name = name,
            number = draft.number,
            order = draft.order,
            slotRounding = draft.rounding,
            icon = draft.icon,
            runs = draft.runs,
            source = draft.source,
        )
        return when (val u = repository.update(updated)) {
            is Outcome.Failure -> u
            is Outcome.Success -> if (sourceChanged) {
                repository.refreshLineup(channel.id, applyNow = false)
            } else {
                u
            }
        }
    }

    /** Details step after Apply from Edit Selections (source only; name and other fields stay). */
    fun applyToDraft(draft: EditorDraft, source: ProgrammingSource): EditorStep.Details =
        EditorStep.Details(draft.copy(source = source, problem = null))
}
