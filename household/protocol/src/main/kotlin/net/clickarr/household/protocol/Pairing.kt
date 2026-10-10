package net.clickarr.household.protocol

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN-bound proof for pairing (proposal 13.4). The joiner proves it knows the 6-digit PIN the
 * coordinator is displaying, bound to both TLS certificate fingerprints so a proof captured in one
 * session is useless in another:
 *
 *   key   = HKDF-SHA256(ikm = PIN, salt = nonce, info = "clickarr-pair-v1")
 *   proof = HMAC-SHA256(key, sessionId || "|" || joinerFingerprint || "|" || coordinatorFingerprint)
 *
 * Both sides compute it; the coordinator compares in constant time and allows three attempts.
 */
object Pairing {
    const val PIN_LENGTH = 6
    const val MAX_ATTEMPTS = 3
    const val PIN_LIFETIME_SECONDS = 120
    private const val INFO = "clickarr-pair-v1"

    fun randomPin(random: SecureRandom = SecureRandom()): String =
        (1..PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

    fun randomNonce(random: SecureRandom = SecureRandom()): String = randomHex(16, random)

    fun randomToken(random: SecureRandom = SecureRandom()): String = randomHex(32, random)

    fun proof(pin: String, nonce: String, sessionId: String, joinerFingerprint: String, coordinatorFingerprint: String): String {
        val key = hkdf(ikm = pin.toByteArray(), salt = nonce.toByteArray(), info = INFO.toByteArray(), length = 32)
        val message = "$sessionId|$joinerFingerprint|$coordinatorFingerprint".toByteArray()
        return hmac(key, message).toHex()
    }

    fun verify(expected: String, presented: String): Boolean {
        val a = expected.toByteArray()
        val b = presented.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Formats a PIN as "482 913" for the coordinator's screen. */
    fun display(pin: String): String = pin.chunked(3).joinToString(" ")

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    /** RFC 5869 HKDF with SHA-256, extract then expand. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
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

    private fun randomHex(bytes: Int, random: SecureRandom): String = ByteArray(bytes).also(random::nextBytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
