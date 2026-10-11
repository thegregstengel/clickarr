package net.clickarr.household.client

import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.flatMap
import net.clickarr.core.model.DeviceId
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.Pairing
import net.clickarr.household.protocol.Pairing.toHex

/**
 * The joiner's half of pairing ([Pairing]): send pA, check the coordinator's confirmation, send ours. A wrong PIN,
 * or a device in the middle, fails the check here, before anything is sent that could help a second guess.
 */
object HouseholdJoin {
    suspend fun join(
        client: HouseholdClient,
        deviceId: DeviceId,
        deviceName: String,
        ownFingerprint: String,
        coordinatorFingerprint: String,
        pin: String,
    ): Outcome<PairCompleteResponse>
    {
        val pake = Pairing.joiner(pin, ownFingerprint, coordinatorFingerprint)
        return client.pairStart(PairStartRequest(deviceId, deviceName, ownFingerprint, pake.message.toHex())).flatMap { start ->
            val theirs = Pairing.fromHex(start.pake)
            val keys = theirs?.let { runCatching { pake.finish(it, Pairing.aad(start.sessionId)) }.getOrNull() }
            if (keys == null || !Pairing.verify(keys.confirmB.toHex(), start.confirm)) {
                Outcome.Failure(ClickarrError.Unauthorized("Wrong PIN, or that is not the TV showing it"))
            } else {
                client.pairComplete(PairCompleteRequest(start.sessionId, keys.confirmA.toHex()))
            }
        }
    }
}
