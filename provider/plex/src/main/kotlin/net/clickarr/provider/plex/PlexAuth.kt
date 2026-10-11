package net.clickarr.provider.plex

import kotlinx.serialization.builtins.ListSerializer
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.model.ProviderKind
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.DiscoveredServer
import okhttp3.OkHttpClient

/**
 * plex.tv sign-in for televisions: show a 4-character code, the user enters it at plex.tv/link,
 * poll until claimed, then list the account's servers. Proposal section 6.2.
 */
class PlexAuth(client: OkHttpClient, identity: ClientIdentity, private val plexTvBase: String = "https://plex.tv") {
    private var token: String? = null
    private val http = PlexHttp(client, identity) { token }

    data class Pin(val id: Long, val code: String, val expiresAt: String?)

    suspend fun createPin(): Outcome<Pin> =
        http.post(PlexHttp.url(plexTvBase, "/api/v2/pins", "strong" to "false"), PlexPin.serializer())
            .map { Pin(it.id, it.code, it.expiresAt) }

    /** Returns the account token once the user has entered the code, or null while still waiting. */
    suspend fun checkPin(pin: Pin): Outcome<String?> =
        http.get(PlexHttp.url(plexTvBase, "/api/v2/pins/${pin.id}"), PlexPin.serializer()).map { it.authToken?.ifBlank { null } }

    /** Servers this account can reach, with the per-server token to use for each. */
    suspend fun servers(accountToken: String): Outcome<List<PlexServerCandidate>> {
        token = accountToken
        val url = PlexHttp.url(plexTvBase, "/api/v2/resources", "includeHttps" to "1", "includeRelay" to "0")
        return http.get(url, ListSerializer(PlexResource.serializer())).flatMap { resources ->
            Outcome.Success(
                resources.filter { it.provides.split(',').contains("server") }.map { r ->
                    PlexServerCandidate(
                        server = DiscoveredServer(
                            kind = ProviderKind.PLEX,
                            serverIdentity = r.clientIdentifier,
                            name = r.name,
                            urls = r.connections
                                .sortedWith(
                                    compareByDescending<PlexConnection> { it.local }
                                        .thenByDescending { it.protocol.equals("https", ignoreCase = true) }
                                        .thenBy { it.relay },
                                )
                                .map { it.uri },
                            version = r.productVersion,
                            owned = r.owned,
                        ),
                        token = r.accessToken ?: accountToken,
                    )
                },
            )
        }
    }
}

data class PlexServerCandidate(val server: DiscoveredServer, val token: String)
