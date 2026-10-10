package net.clickarr.core.database

import kotlinx.datetime.Instant
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerLocation

fun HouseholdDevice.toEntity() = HouseholdDeviceEntity(id.value, name, joinedAt.toEpochMilliseconds(), lastSeen?.toEpochMilliseconds())

fun HouseholdDeviceEntity.toModel() = HouseholdDevice(
    DeviceId(deviceId), name, Instant.fromEpochMilliseconds(joinedAtEpochMs), lastSeenEpochMs?.let(Instant::fromEpochMilliseconds),
)

fun ServerLocation.toEntity() = HouseholdServerEntity(serverIdentity, kind.name, name, DbJson.json.encodeToString(DbJson.strings, urls))

fun HouseholdServerEntity.toModel() =
    ServerLocation(ProviderKind.valueOf(kind), serverIdentity, name, DbJson.json.decodeFromString(DbJson.strings, urls))
