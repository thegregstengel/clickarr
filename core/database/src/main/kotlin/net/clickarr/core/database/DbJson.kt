package net.clickarr.core.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.clickarr.core.model.MediaVersion

/** Shared JSON configuration for nested columns. */
internal object DbJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val strings = ListSerializer(String.serializer())
    val versions = ListSerializer(MediaVersion.serializer())
}

const val TYPE_SHOW = "SHOW"
const val TYPE_SEASON = "SEASON"
const val TYPE_EPISODE = "EPISODE"
const val TYPE_MOVIE = "MOVIE"
