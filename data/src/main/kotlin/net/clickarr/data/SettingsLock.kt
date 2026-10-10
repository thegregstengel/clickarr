package net.clickarr.data

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The settings lock code (Settings, General, "Settings lock"): four digits that keep children out of
 * Settings and the channel editor. Stored salted and hashed in device-local preferences, never
 * synchronized. A kids lock, not a vault: the recovery for a forgotten code is clearing the app's data.
 */
object SettingsLock {
    const val LENGTH = 4

    /** What gets stored: "salt:sha256(salt + code)" in hex. */
    fun record(code: String, random: SecureRandom = SecureRandom()): String {
        require(code.length == LENGTH && code.all(Char::isDigit)) { "a lock code is $LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return "$salt:${digest(salt, code)}"
    }

    fun matches(code: String, record: String): Boolean {
        val salt = record.substringBefore(':', missingDelimiterValue = "")
        val hash = record.substringAfter(':', missingDelimiterValue = "")
        return salt.isNotEmpty() && hash.isNotEmpty() && constantTimeEquals(digest(salt, code), hash)
    }

    /**
     * Wrong guesses are rate limited: after [maxFailures] in a row the pad sleeps for [cooldownMs].
     * Pure bookkeeping; the caller supplies the clock.
     */
    class Attempts(private val maxFailures: Int = MAX_FAILURES, private val cooldownMs: Long = COOLDOWN_MS) {
        private var failures = 0
        private var lockedUntil = 0L

        /** Milliseconds left in the cooldown, or zero when a guess may be made. */
        fun waitMs(nowMs: Long): Long = (lockedUntil - nowMs).coerceAtLeast(0L)

        fun failed(nowMs: Long) {
            failures++
            if (failures >= maxFailures) {
                failures = 0
                lockedUntil = nowMs + cooldownMs
            }
        }

        fun succeeded() {
            failures = 0
            lockedUntil = 0L
        }
    }

    private fun digest(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt$code".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private const val SALT_BYTES = 8
    private const val MAX_FAILURES = 5
    private const val COOLDOWN_MS = 30_000L
}
