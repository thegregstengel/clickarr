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
import net.clickarr.provider.api.MediaProviderFactory
import net.clickarr.provider.api.ProviderCredentials
import kotlinx.serialization.json.Json

/**
 * The live set of connected media servers. Connections live in Room, credentials in the SecretStore,
 * and this class joins them into MediaProvider instances (ADR 0014: credentials never leave the device).
 * One factory per server kind (ADR 0022).
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val db: ClickarrDatabase,
    private val secrets: SecretStore,
    factories: Set<@JvmSuppressWildcards MediaProviderFactory>,
) {
    private val factories: Map<ProviderKind, MediaProviderFactory> = factories.associateBy { it.kind }
    private val _providers = MutableStateFlow<Map<ProviderId, MediaProvider>>(emptyMap())
    val providers: StateFlow<Map<ProviderId, MediaProvider>> = _providers.asStateFlow()

    fun get(id: ProviderId): MediaProvider? = _providers.value[id]

    /** The TV's one server (ADR 0022); the setup, channel editor, and sync use it. */
    val primary: MediaProvider? get() = _providers.value.values.firstOrNull()

    val hasAny: Boolean get() = _providers.value.isNotEmpty()

    /** Rebuild providers from storage. Called once at startup. */
    suspend fun load() {
        val built = db.providerConnections().all().mapNotNull { conn ->
            val info = conn.toServerInfo()
            val stored = secrets.get(SecretStore.providerKey(conn.id))
            val credentials = stored?.let { decodeCredentials(info.kind, it) }
            val factory = factories[info.kind]
            if (credentials == null || factory == null) {
                Log.w(TAG) { "Cannot load ${conn.name} (${info.kind}): ${if (factory == null) "no provider" else "no credentials"}" }
                return@mapNotNull null
            }
            info.providerId to factory.create(info, credentials)
        }
        _providers.value = built.toMap()
    }

    /**
     * Connect a server: try each address in order and keep the first that answers [MediaProvider.ping]. The server's
     * own identity becomes the provider id. Persists the connection and the credentials, then adds the provider.
     */
    suspend fun connect(name: String, urls: List<String>, credentials: ProviderCredentials): Outcome<MediaProvider> {
        val factory = factories[credentials.kind]
            ?: return Outcome.Failure(ClickarrError.Invalid("This build cannot connect to ${credentials.kind.label}"))
        var last: ClickarrError = ClickarrError.Unreachable("No address for $name")
        for (url in urls) {
            val probe = ServerInfo(ProviderId("pending"), credentials.kind, "pending", name, url.trimEnd('/'))
            when (val ping = factory.create(probe, credentials).ping()) {
                is Outcome.Success -> {
                    val info = ping.value.copy(providerId = ProviderId(ping.value.serverIdentity), baseUrl = url.trimEnd('/'))
                    return adopt(factory, info, urls, credentials)
                }
                is Outcome.Failure -> {
                    Log.i(TAG) { "$name not reachable at $url: ${ping.error.message}" }
                    last = ping.error
                }
            }
        }
        return Outcome.Failure(if (urls.size > 1) ClickarrError.Unreachable("Could not reach $name on any address") else last)
    }

    private suspend fun adopt(
        factory: MediaProviderFactory,
        info: ServerInfo,
        urls: List<String>,
        credentials: ProviderCredentials,
    ): Outcome<MediaProvider> {
        oneServerOnly(info)?.let { return Outcome.Failure(it) }
        persist(info, urls, credentials)
        val live = factory.create(info, credentials)
        _providers.update { it + (info.providerId to live) }
        return Outcome.Success(live)
    }

    /**
     * A TV is signed in to one server, Plex or Jellyfin (ADR 0022): channels, sync, and the guide all assume it.
     * Signing in to the same server again is fine (a new token or address); a different one needs a disconnect first.
     */
    private fun oneServerOnly(info: ServerInfo): ClickarrError? {
        val other = _providers.value.values.firstOrNull { it.id != info.providerId } ?: return null
        return ClickarrError.Invalid(
            "This TV is already signed in to ${other.kind.label} server ${other.server.name}. " +
                "Disconnect it in Settings, ${other.kind.label} before signing in to another.",
        )
    }

    suspend fun remove(id: ProviderId) {
        db.providerConnections().delete(id.value)
        secrets.remove(SecretStore.providerKey(id.value))
        _providers.update { it - id }
    }

    private suspend fun persist(info: ServerInfo, altUrls: List<String>, credentials: ProviderCredentials) {
        db.providerConnections().upsert(info.toEntity(altUrls, KxClock.System.now()))
        secrets.put(SecretStore.providerKey(info.providerId.value), json.encodeToString(ProviderCredentials.serializer(), credentials))
    }

    companion object {
        private const val TAG = "Providers"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Credentials as stored: JSON since ADR 0022. Before that the store held a bare Plex token, which is still read
         * so TVs set up earlier keep their sign-in.
         */
        internal fun decodeCredentials(kind: ProviderKind, stored: String): ProviderCredentials? =
            if (stored.startsWith("{")) {
                runCatching { json.decodeFromString(ProviderCredentials.serializer(), stored) }.getOrNull()?.takeIf { it.kind == kind }
            } else if (kind == ProviderKind.PLEX) {
                ProviderCredentials(ProviderKind.PLEX, mapOf(PLEX_TOKEN to stored))
            } else {
                null
            }

        /** The key PlexProviderFactory reads; repeated here so data need not depend on provider:plex. */
        internal const val PLEX_TOKEN = "token"
    }
}
