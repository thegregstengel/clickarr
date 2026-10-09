package net.clickarr.core.scheduling

/**
 * Clickarr's own PRNG so shuffle results are identical on every device and every app version.
 * Platform generators (java.util.Random, kotlin.random.Random) do not promise a stable algorithm, so
 * they are not part of the determinism contract (ADR 0008).
 *
 * SplitMix64 expands a seed into state; xoshiro256** produces the stream. Both are public domain
 * algorithms by Vigna (and Steele/Lea/Flood for SplitMix). Test vectors live in DeterministicRandomTest.
 */
class DeterministicRandom(seed: Long) {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long

    init {
        val sm = SplitMix64(seed)
        s0 = sm.next()
        s1 = sm.next()
        s2 = sm.next()
        s3 = sm.next()
    }

    /** Next 64 bits from xoshiro256**. */
    fun nextLong(): Long {
        val result = java.lang.Long.rotateLeft(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    /** Uniform integer in [0, bound) using unbiased rejection sampling. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val b = bound.toLong()
        // Lemire-style rejection over the top 32 bits keeps this unbiased for any bound.
        while (true) {
            val r = nextLong() ushr 32
            val m = r * b
            val l = m and 0xFFFFFFFFL
            if (l >= ((1L shl 32) % b)) return (m ushr 32).toInt()
        }
    }

    /** Fisher-Yates shuffle of 0 until n, driven by this generator. */
    fun permutation(n: Int): IntArray {
        val p = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
        }
        return p
    }

    companion object {
        /**
         * Seed for one cycle of one channel. Mixing the cycle through SplitMix keeps cycles 0 and 1
         * uncorrelated even though the channel seed is fixed.
         */
        fun forCycle(channelSeed: Long, cycle: Long): DeterministicRandom =
            DeterministicRandom(channelSeed xor SplitMix64(cycle).next())
    }
}

/** SplitMix64. Public domain reference algorithm. */
class SplitMix64(private var state: Long) {
    fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
}
