package net.clickarr.provider.api

import kotlinx.serialization.Serializable
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo

/**
 * Per-kind construction and sign-in. Credentials are opaque to everything but the provider that
 * issued them and the SecretStore that encrypts them (ADR 0014).
 */
interface MediaProviderFactory {
    val kind: ProviderKind

    fun create(server: ServerInfo, credentials: ProviderCredentials): MediaProvider
}

/** Opaque, serializable credential blob. Never logged, never synchronized. */
@Serializable
data class ProviderCredentials(val kind: ProviderKind, val values: Map<String, String>) {
    override fun toString(): String = "ProviderCredentials(kind=$kind, <redacted>)"
}

/** A server the user could connect to, as discovered during sign-in. */
@Serializable
data class DiscoveredServer(
    val kind: ProviderKind,
    val serverIdentity: String,
    val name: String,
    /** Candidate base URLs, best first (local before remote). */
    val urls: List<String>,
    val version: String? = null,
    val owned: Boolean = true,
)

/** Identity this install presents to servers. Stable per install. */
data class ClientIdentity(
    val deviceId: ProviderId,
    val deviceName: String,
    val appVersion: String,
    val platform: String = "Android",
) {
    constructor(deviceId: String, deviceName: String, appVersion: String) : this(ProviderId(deviceId), deviceName, appVersion)
}

typealias OutcomeOf<T> = Outcome<T>
