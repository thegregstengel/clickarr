package net.clickarr.provider.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plex Media Server JSON shapes (with Accept: application/json). Only the fields Clickarr reads.
 * These never leave this module; PlexMapper turns them into core:model types.
 */
internal val plexJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable
internal data class PlexContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    val machineIdentifier: String? = null,
    val version: String? = null,
    val friendlyName: String? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

@Serializable
internal data class PlexDirectory(
    val key: String,
    val type: String = "",
    val title: String = "",
    val uuid: String? = null,
)

@Serializable
internal data class PlexTag(val tag: String = "")

@Serializable
internal data class PlexMetadata(
    val ratingKey: String,
    val key: String? = null,
    val type: String = "",
    val title: String = "",
    val year: Int? = null,
    val studio: String? = null,
    /** Milliseconds. Metadata-level duration; prefer Media.duration when present. */
    val duration: Long? = null,
    val thumb: String? = null,
    val art: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val leafCount: Int? = null,
    val childCount: Int? = null,
    val contentRating: String? = null,
    val originallyAvailableAt: String? = null,
    val playlistType: String? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Label") val labels: List<PlexTag> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
)

@Serializable
internal data class PlexMedia(
    val id: Long? = null,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
internal data class PlexPart(
    val id: Long? = null,
    val key: String? = null,
    val duration: Long? = null,
    val container: String? = null,
)

// plex.tv account API (api/v2)

@Serializable
internal data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresAt: String? = null,
)

@Serializable
internal data class PlexResource(
    val name: String = "",
    val provides: String = "",
    val clientIdentifier: String = "",
    val accessToken: String? = null,
    val owned: Boolean = true,
    val productVersion: String? = null,
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable
internal data class PlexConnection(
    val protocol: String = "http",
    val address: String = "",
    val port: Int = 32400,
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
)
