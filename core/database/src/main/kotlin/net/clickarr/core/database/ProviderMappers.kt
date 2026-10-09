package net.clickarr.core.database

import kotlinx.datetime.Instant
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo

fun ServerInfo.toEntity(altUrls: List<String> = emptyList(), lastOkAt: Instant? = null) = ProviderConnectionEntity(
    id = providerId.value,
    kind = kind.name,
    serverIdentity = serverIdentity,
    name = name,
    baseUrl = baseUrl,
    altUrls = DbJson.json.encodeToString(DbJson.strings, altUrls),
    version = version,
    lastOkAt = lastOkAt?.toEpochMilliseconds(),
)

fun ProviderConnectionEntity.toServerInfo() =
    ServerInfo(ProviderId(id), ProviderKind.valueOf(kind), serverIdentity, name, baseUrl, version)

fun ProviderConnectionEntity.altUrlList(): List<String> = DbJson.json.decodeFromString(DbJson.strings, altUrls)

fun Library.toEntity() = LibraryEntity(ref.provider.value, ref.id.value, name, kind.name)

fun LibraryEntity.toModel() = Library(MediaRef(ProviderId(providerId), NativeItemId(nativeId)), name, LibraryKind.valueOf(kind))
