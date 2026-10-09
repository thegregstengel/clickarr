package net.clickarr.core.scheduling

import java.security.MessageDigest
import kotlinx.datetime.Instant
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.LineupEntry
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.LineupSnapshotId

/** Builds and fingerprints lineup snapshots. The hash is what two devices compare to know they agree. */
object Lineups {
    /**
     * SHA-256 over (provider, item id, duration in ms) for every entry in order. Titles are excluded on
     * purpose: a metadata refresh that only fixes a title must not look like a different lineup.
     */
    fun contentHash(entries: List<LineupEntry>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries) {
            md.update(e.ref.provider.value.toByteArray())
            md.update(0)
            md.update(e.ref.id.value.toByteArray())
            md.update(0)
            md.update(e.duration.inWholeMilliseconds.toString().toByteArray())
            md.update('\n'.code.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun create(id: LineupSnapshotId, channelId: ChannelId, entries: List<LineupEntry>, createdAt: Instant): LineupSnapshot {
        require(entries.all { it.duration.isPositive() }) { "every lineup entry needs a positive duration" }
        return LineupSnapshot(id = id, channelId = channelId, createdAt = createdAt, entries = entries, contentHash = contentHash(entries))
    }
}
