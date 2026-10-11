package net.clickarr.household.protocol

import java.security.SecureRandom

/**
 * Pairing a TV into a household (ADR 0013, amended 2026-10-11). The coordinator shows a six-digit PIN; the joiner
 * and the coordinator run [Spake2] with it, each naming the pair of TLS certificate fingerprints it saw, and
 * prove the result to each other:
 *
 *   1. joiner -> coordinator: pA                     (pair/start)
 *   2. coordinator -> joiner: pB, B's confirmation   (the joiner checks it: a wrong PIN or a relay fails here)
 *   3. joiner -> coordinator: A's confirmation       (pair/complete; the coordinator then issues the token)
 *
 * Nothing on the wire lets anyone test PIN guesses offline. Each pair/start is one guess against the PIN, and the
 * coordinator cancels the PIN after [MAX_ATTEMPTS].
 */
object Pairing {
    const val PIN_LENGTH = 6
    const val MAX_ATTEMPTS = 3
    const val PIN_LIFETIME_SECONDS = 120
    private const val AAD = "clickarr-pair-v2"

    fun randomPin(random: SecureRandom = SecureRandom()): String =
        (1..PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

    fun randomToken(random: SecureRandom = SecureRandom()): String = ByteArray(32).also(random::nextBytes).toHex()

    /** The joiner's side; [coordinatorFingerprint] is the certificate it actually saw on the TLS connection. */
    fun joiner(pin: String, joinerFingerprint: String, coordinatorFingerprint: String, random: SecureRandom = SecureRandom()): Spake2 =
        Spake2.start(Spake2.Role.A, joinerFingerprint, coordinatorFingerprint, w(pin, joinerFingerprint, coordinatorFingerprint), random)

    /** The coordinator's side; [coordinatorFingerprint] is its own certificate. */
    fun coordinator(pin: String, joinerFingerprint: String, coordinatorFingerprint: String, random: SecureRandom = SecureRandom()): Spake2 =
        Spake2.start(Spake2.Role.B, joinerFingerprint, coordinatorFingerprint, w(pin, joinerFingerprint, coordinatorFingerprint), random)

    /** Associated data for the confirmation keys: the protocol label and the session, so a confirmation fits one session. */
    fun aad(sessionId: String): ByteArray = "$AAD|$sessionId".toByteArray()

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

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray? = runCatching {
        require(s.length % 2 == 0)
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private fun w(pin: String, joiner: String, coordinator: String) = Spake2.pinScalar(pin.filter(Char::isDigit), joiner, coordinator)
}
