package net.clickarr.core.model

import kotlin.time.Duration
import kotlinx.serialization.Serializable

@Serializable
/** Clickarr supports Plex. The provider boundary stays so the fake provider and tests can stand in for it. */
enum class ProviderKind { PLEX }

@Serializable
data class ServerInfo(
    val providerId: ProviderId,
    val kind: ProviderKind,
    /** Stable across URL changes: the Plex machineIdentifier. */
    val serverIdentity: String,
    val name: String,
    val baseUrl: String,
    val version: String? = null,
)

@Serializable
enum class LibraryKind { MOVIES, SHOWS, OTHER }

@Serializable
data class Library(val ref: MediaRef, val name: String, val kind: LibraryKind)

@Serializable
data class ArtworkRef(val provider: ProviderId, val path: String)

@Serializable
data class Artwork(
    val poster: ArtworkRef? = null,
    val thumb: ArtworkRef? = null,
    val backdrop: ArtworkRef? = null,
)

@Serializable
data class MediaVersion(
    val id: String,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int?,
    val height: Int?,
    val bitrateKbps: Int?,
    /** File duration as reported by the server. This is what schedules are built from. */
    val duration: Duration,
)

/** Anything that can be put in a lineup. */
sealed interface Playable {
    val ref: MediaRef
    val runtime: Duration
    val media: List<MediaVersion>
}

sealed interface MediaItem {
    val ref: MediaRef
    val title: String
    val artwork: Artwork
    val genres: List<String>
    val year: Int?
}

@Serializable
data class Show(
    override val ref: MediaRef,
    override val title: String,
    override val artwork: Artwork = Artwork(),
    override val genres: List<String> = emptyList(),
    override val year: Int? = null,
    val studio: String? = null,
    val network: String? = null,
    val seasonCount: Int = 0,
    val episodeCount: Int = 0,
    /** Plex's synopsis, for the guide's preview card. */
    val summary: String? = null,
) : MediaItem

@Serializable
data class Season(
    override val ref: MediaRef,
    override val title: String,
    override val artwork: Artwork = Artwork(),
    override val genres: List<String> = emptyList(),
    override val year: Int? = null,
    val show: MediaRef,
    val index: Int,
) : MediaItem

@Serializable
data class Episode(
    override val ref: MediaRef,
    override val title: String,
    override val artwork: Artwork = Artwork(),
    override val genres: List<String> = emptyList(),
    override val year: Int? = null,
    val show: MediaRef,
    val showTitle: String,
    val season: MediaRef?,
    val seasonIndex: Int,
    val episodeIndex: Int,
    override val runtime: Duration,
    override val media: List<MediaVersion> = emptyList(),
    /** Plex's synopsis, for the guide's preview card. */
    val summary: String? = null,
) : MediaItem, Playable

@Serializable
data class Movie(
    override val ref: MediaRef,
    override val title: String,
    override val artwork: Artwork = Artwork(),
    override val genres: List<String> = emptyList(),
    override val year: Int? = null,
    val studio: String? = null,
    override val runtime: Duration,
    override val media: List<MediaVersion> = emptyList(),
    /** Plex's synopsis, for the guide's preview card. */
    val summary: String? = null,
) : MediaItem, Playable

@Serializable
data class Collection(val ref: MediaRef, val name: String)

@Serializable
data class Playlist(val ref: MediaRef, val name: String)
