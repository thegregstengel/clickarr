package net.clickarr.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock as KxClock
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.database.toEntity
import net.clickarr.core.database.toServerInfo
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.secrets.SecretStore
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.plex.PlexProviderFactory
import net.clickarr.provider.plex.PlexServerCandidate

/**
 * The live set of connected media servers. Connections live in Room, tokens in the SecretStore,
 * and this class joins them into MediaProvider instances (ADR 0014: tokens never leave the device).
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val db: ClickarrDatabase,
    private val secrets: SecretStore,
    private val plexFactory: PlexProviderFactory,
) {
    private val _providers = MutableStateFlow<Map<ProviderId, MediaProvider>>(emptyMap())
    val providers: StateFlow<Map<ProviderId, MediaProvider>> = _providers.asStateFlow()

    fun get(id: ProviderId): MediaProvider? = _providers.value[id]

    /** The MVP is single-server; this is the one the setup and channel editor use. */
    val primary: MediaProvider? get() = _providers.value.values.firstOrNull()

    val hasAny: Boolean get() = _providers.value.isNotEmpty()

    /** Rebuild providers from storage. Called once at startup. */
    suspend fun load() {
        val built = db.providerConnections().all().mapNotNull { conn ->
            val token = secrets.get(SecretStore.providerKey(conn.id))
            if (token == null) {
                Log.w(TAG) { "No credentials for ${conn.name}; skipping" }
                return@mapNotNull null
            }
            val info = conn.toServerInfo()
            info.providerId to plexFactory.create(info, PlexProviderFactory.credentials(token))
        }
        _providers.value = built.toMap()
    }

    /**
     * Connect a Plex server found through plex.tv. Tries each candidate URL, local first, and keeps the
     * first that answers. Persists the connection and the token, then adds the provider.
     */
    suspend fun addPlex(candidate: PlexServerCandidate): Outcome<MediaProvider> {
        val id = ProviderId(candidate.server.serverIdentity)
        for (url in candidate.server.urls) {
            val server = candidate.server
            val info = ServerInfo(id, ProviderKind.PLEX, server.serverIdentity, server.name, url, server.version)
            val provider = plexFactory.create(info, PlexProviderFactory.credentials(candidate.token))
            when (val ping = provider.ping()) {
                is Outcome.Success -> {
                    val verified = ping.value.copy(baseUrl = url)
                    persist(verified, candidate.server.urls, candidate.token)
                    val live = plexFactory.create(verified, PlexProviderFactory.credentials(candidate.token))
                    _providers.update { it + (id to live) }
                    return Outcome.Success(live)
                }
                is Outcome.Failure -> Log.i(TAG) { "${candidate.server.name} not reachable at $url: ${ping.error.message}" }
            }
        }
        return Outcome.Failure(ClickarrError.Unreachable("Could not reach ${candidate.server.name} on any address"))
    }

    /** Manual connection: base URL plus a token the user already has. */
    suspend fun addPlexManual(baseUrl: String, token: String): Outcome<MediaProvider> {
        val probe = ServerInfo(ProviderId("pending"), ProviderKind.PLEX, "pending", baseUrl, baseUrl.trimEnd('/'))
        val provider = plexFactory.create(probe, PlexProviderFactory.credentials(token))
        return when (val ping = provider.ping()) {
            is Outcome.Success -> {
                val info = ping.value.copy(providerId = ProviderId(ping.value.serverIdentity))
                persist(info, emptyList(), token)
                val live = plexFactory.create(info, PlexProviderFactory.credentials(token))
                _providers.update { it + (info.providerId to live) }
                Outcome.Success(live)
            }
            is Outcome.Failure -> ping
        }
    }

    suspend fun remove(id: ProviderId) {
        db.providerConnections().delete(id.value)
        secrets.remove(SecretStore.providerKey(id.value))
        _providers.update { it - id }
    }

    private suspend fun persist(info: ServerInfo, altUrls: List<String>, token: String) {
        db.providerConnections().upsert(info.toEntity(altUrls, KxClock.System.now()))
        secrets.put(SecretStore.providerKey(info.providerId.value), token)
    }

    companion object {
        private const val TAG = "Providers"
    }
}
