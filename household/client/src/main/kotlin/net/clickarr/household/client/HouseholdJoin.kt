package net.clickarr.household.client

import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.model.DeviceId
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.Pairing

/** The joiner's half of pairing (proposal 13.4): start, derive the proof from the PIN, complete. */
object HouseholdJoin {
    suspend fun join(
        client: HouseholdClient,
        deviceId: DeviceId,
        deviceName: String,
        ownFingerprint: String,
        coordinatorFingerprint: String,
        pin: String,
    ): Outcome<PairCompleteResponse> =
        client.pairStart(PairStartRequest(deviceId, deviceName, ownFingerprint)).flatMap { start ->
            val proof = Pairing.proof(pin.filter { it.isDigit() }, start.nonce, start.sessionId, ownFingerprint, coordinatorFingerprint)
            client.pairComplete(PairCompleteRequest(start.sessionId, proof))
        }
}
