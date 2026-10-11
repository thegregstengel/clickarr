package net.clickarr.household.protocol

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint

/**
 * SPAKE2 over P-256 with SHA-256, HKDF, and HMAC, as specified by RFC 9382 and checked against its test vectors.
 *
 * Two parties who share a low-entropy secret (the pairing PIN, through [w]) agree on a key, and a transcript
 * reveals nothing that lets an observer, or an impostor on either side, test PIN guesses offline: each attempt
 * is one online guess. A is the joining TV, B the coordinator. Their identities are the TLS certificate
 * fingerprints each one saw, so a device relaying between them ends up with mismatched transcripts.
 */
class Spake2 internal constructor(
    private val role: Role,
    private val idA: ByteArray,
    private val idB: ByteArray,
    private val w: BigInteger,
    private val secret: BigInteger,
) {
    enum class Role { A, B }

    /** The keys both sides derive, and the two confirmation codes (A's, then B's) over the transcript. */
    class Keys(val ke: ByteArray, val confirmA: ByteArray, val confirmB: ByteArray)

    /** This side's public message: pA = x·G + w·M for A, pB = y·G + w·N for B, uncompressed. */
    val message: ByteArray = G.multiply(secret).add(blind(role).multiply(w)).normalize().getEncoded(false)

    /** Completes the exchange with the peer's message; throws [IllegalArgumentException] on a malformed or degenerate point. */
    fun finish(peerMessage: ByteArray, aad: ByteArray = ByteArray(0)): Keys {
        val peer = runCatching { CURVE.curve.decodePoint(peerMessage) }.getOrNull()
        require(peer != null && peer.isValid && !peer.isInfinity) { "invalid SPAKE2 message" }
        val peerBlind = if (role == Role.A) N else M
        val k = peer.subtract(peerBlind.multiply(w)).multiply(secret).normalize()
        require(!k.isInfinity) { "degenerate SPAKE2 key" }
        val (pA, pB) = if (role == Role.A) message to peerMessage else peerMessage to message
        val tt = transcript(pA, pB, k.getEncoded(false))
        val hash = MessageDigest.getInstance("SHA-256").digest(tt)
        val ke = hash.copyOfRange(0, HALF)
        val ka = hash.copyOfRange(HALF, HASH)
        val kc = hkdf(ikm = ka, salt = ByteArray(0), info = "ConfirmationKeys".toByteArray() + aad, length = HASH)
        return Keys(ke, hmac(kc.copyOfRange(0, HALF), tt), hmac(kc.copyOfRange(HALF, HASH), tt))
    }

    private fun transcript(pA: ByteArray, pB: ByteArray, k: ByteArray): ByteArray {
        return listOf(idA, idB, pA, pB, k, fixed(w)).fold(ByteArray(0)) { acc, part -> acc + lengthPrefix(part.size) + part }
    }

    companion object {
        private val CURVE = CustomNamedCurves.getByName("secp256r1")
        private val G: ECPoint = CURVE.g
        private val ORDER: BigInteger = CURVE.n

        // RFC 9382, section 6: the P-256 points M and N, generated so nobody knows their discrete logarithms.
        private val M: ECPoint = CURVE.curve.decodePoint(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
        private val N: ECPoint = CURVE.curve.decodePoint(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

        private const val HASH = 32
        private const val HALF = 16
        private const val SCALAR = 32
        private const val W_BYTES = 48
        private const val LENGTH_BYTES = 8
        private const val W_SALT = "clickarr-spake2-p256-v2"

        private fun blind(role: Role): ECPoint = if (role == Role.A) M else N

        /** A scalar as big-endian bytes padded to the field size, the encoding RFC 9382 uses for w in the transcript. */
        private fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            val src = if (raw.size > SCALAR) raw.copyOfRange(raw.size - SCALAR, raw.size) else raw
            return ByteArray(SCALAR).also { src.copyInto(it, SCALAR - src.size) }
        }

        /** A fresh exchange for [role], with a random secret scalar and [w] from [pinScalar]. */
        fun start(role: Role, idA: String, idB: String, w: BigInteger, random: SecureRandom = SecureRandom()): Spake2 =
            Spake2(role, idA.toByteArray(), idB.toByteArray(), w, randomScalar(random))

        /**
         * w for a PIN: HKDF-SHA256 over the digits, bound to both identities, 48 bytes reduced modulo the group
         * order (the extra 16 bytes make the reduction bias negligible, as RFC 9382 recommends).
         */
        fun pinScalar(pin: String, idA: String, idB: String): BigInteger {
            val info = lengthPrefix(idA.length) + idA.toByteArray() + lengthPrefix(idB.length) + idB.toByteArray()
            val okm = hkdf(ikm = pin.toByteArray(), salt = W_SALT.toByteArray(), info = info, length = W_BYTES)
            return BigInteger(1, okm).mod(ORDER)
        }

        private fun randomScalar(random: SecureRandom): BigInteger {
            while (true) {
                val candidate = BigInteger(1, ByteArray(SCALAR).also(random::nextBytes))
                if (candidate.signum() > 0 && candidate < ORDER) return candidate
            }
        }

        private fun lengthPrefix(n: Int): ByteArray =
            ByteBuffer.allocate(LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(n.toLong()).array()

        internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH) else key, "HmacSHA256")) }
                .doFinal(message)

        /** RFC 5869 HKDF with SHA-256. An empty salt means a block of zeros, as the RFC specifies. */
        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmac(salt, ikm)
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                val n = minOf(previous.size, length - filled)
                System.arraycopy(previous, 0, out, filled, n)
                filled += n
                counter++
            }
            return out
        }
    }
}
