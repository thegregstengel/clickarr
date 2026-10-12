package net.clickarr.provider.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The parts of Jellyfin's JSON that Clickarr reads, written from the published REST API (ADR 0022). */

internal val jellyfinJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class JfSystemInfo(
    @SerialName("Id") val id: String,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

@Serializable
internal data class JfItems(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int? = null,
)

@Serializable
internal data class JfNamed(@SerialName("Name") val name: String)

@Serializable
internal data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<JfNamed> = emptyList(),
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropTags: List<String> = emptyList(),
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryTag: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonIndex: Int? = null,
    @SerialName("IndexNumber") val index: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveCount: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
)

@Serializable
internal data class JfMediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val streams: List<JfStream> = emptyList(),
)

@Serializable
internal data class JfStream(
    @SerialName("Type") val type: String,
    @SerialName("Index") val index: Int,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isText: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
internal data class JfPlaybackInfo(
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
internal data class JfUser(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

@Serializable
internal data class JfAuthResult(@SerialName("AccessToken") val accessToken: String, @SerialName("User") val user: JfUser)

@Serializable
internal data class JfQuickConnect(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
)
