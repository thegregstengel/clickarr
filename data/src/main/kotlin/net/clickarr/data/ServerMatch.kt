package net.clickarr.data

import net.clickarr.core.common.ClickarrError
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.model.ServerLocation

/**
 * A household, LAN or Drive, shares one media server (ADR 0022): its channels point at that server's items. These
 * checks keep a TV signed in to another server, or another kind of server, from taking a lineup it cannot play.
 */
internal object ServerMatch {
    fun location(mine: ServerInfo) = ServerLocation(mine.kind, mine.serverIdentity, mine.name, listOf(mine.baseUrl))

    fun listed(mine: ServerInfo, servers: List<ServerLocation>): Boolean =
        servers.any { it.kind == mine.kind && it.serverIdentity == mine.serverIdentity }

    /** Null when [servers] is empty (nothing recorded yet) or includes [mine]; otherwise why they do not match. */
    fun mismatch(mine: ServerInfo?, servers: List<ServerLocation>, what: String): ClickarrError? {
        if (mine == null || servers.isEmpty() || listed(mine, servers)) return null
        val other = servers.first()
        return ClickarrError.Invalid(
            "This $what uses ${other.kind.label} server ${other.name}; this TV is signed in to ${mine.kind.label} server ${mine.name}.",
        )
    }
}
