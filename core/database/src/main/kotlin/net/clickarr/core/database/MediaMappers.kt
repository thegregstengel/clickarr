package net.clickarr.core.database

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import net.clickarr.core.model.Artwork
import net.clickarr.core.model.Episode
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.Season
import net.clickarr.core.model.Show

fun MediaItem.toEntity(fetchedAt: Instant): MediaItemEntity {
    val base = MediaItemEntity(
        providerId = ref.provider.value,
        nativeId = ref.id.value,
        type = "",
        title = title,
        year = year,
        parentNativeId = null,
        grandparentNativeId = null,
        grandparentTitle = null,
        seasonIndex = null,
        episodeIndex = null,
        runtimeMs = null,
        genres = DbJson.json.encodeToString(DbJson.strings, genres),
        studio = null,
        network = null,
        artwork = DbJson.json.encodeToString(Artwork.serializer(), artwork),
        versions = "[]",
        seasonCount = null,
        episodeCount = null,
        fetchedAt = fetchedAt.toEpochMilliseconds(),
    )
    return when (this) {
        is Show -> base.copy(
            type = TYPE_SHOW, studio = studio, network = network, seasonCount = seasonCount, episodeCount = episodeCount,
        )
        is Season -> base.copy(type = TYPE_SEASON, parentNativeId = show.id.value, seasonIndex = index)
        is Episode -> base.copy(
            type = TYPE_EPISODE,
            parentNativeId = season?.id?.value,
            grandparentNativeId = show.id.value,
            grandparentTitle = showTitle,
            seasonIndex = seasonIndex,
            episodeIndex = episodeIndex,
            runtimeMs = runtime.inWholeMilliseconds,
            versions = DbJson.json.encodeToString(DbJson.versions, media),
        )
        is Movie -> base.copy(
            type = TYPE_MOVIE,
            studio = studio,
            runtimeMs = runtime.inWholeMilliseconds,
            versions = DbJson.json.encodeToString(DbJson.versions, media),
        )
    }
}

fun MediaItemEntity.toModel(): MediaItem? = when (type) {
    TYPE_SHOW -> toShow()
    TYPE_SEASON -> toSeason()
    TYPE_EPISODE -> toEpisode()
    TYPE_MOVIE -> toMovie()
    else -> null
}

private val MediaItemEntity.ref get() = MediaRef(ProviderId(providerId), NativeItemId(nativeId))
private val MediaItemEntity.art get() = DbJson.json.decodeFromString(Artwork.serializer(), artwork)
private val MediaItemEntity.genreList get() = DbJson.json.decodeFromString(DbJson.strings, genres)
private val MediaItemEntity.versionList get() = DbJson.json.decodeFromString(DbJson.versions, versions)
private fun MediaItemEntity.refTo(id: String?) = MediaRef(ProviderId(providerId), NativeItemId(id ?: ""))

private fun MediaItemEntity.toShow() = Show(ref, title, art, genreList, year, studio, network, seasonCount ?: 0, episodeCount ?: 0)

private fun MediaItemEntity.toSeason() = Season(ref, title, art, genreList, year, refTo(parentNativeId), seasonIndex ?: 0)

private fun MediaItemEntity.toEpisode() = Episode(
    ref = ref,
    title = title,
    artwork = art,
    genres = genreList,
    year = year,
    show = refTo(grandparentNativeId),
    showTitle = grandparentTitle ?: "",
    season = parentNativeId?.let { refTo(it) },
    seasonIndex = seasonIndex ?: 0,
    episodeIndex = episodeIndex ?: 0,
    runtime = (runtimeMs ?: 0L).milliseconds,
    media = versionList,
)

private fun MediaItemEntity.toMovie() = Movie(ref, title, art, genreList, year, studio, (runtimeMs ?: 0L).milliseconds, versionList)
