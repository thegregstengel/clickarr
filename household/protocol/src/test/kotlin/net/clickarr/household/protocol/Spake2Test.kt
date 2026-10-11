package net.clickarr.household.protocol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigInteger
import net.clickarr.household.protocol.Pairing.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Spake2Test {
    // RFC 9382, Appendix B, the first SPAKE2-P256-SHA256-HKDF-HMAC vector (A = "server", B = "client", no AAD).
    private val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
    private val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
    private val y = BigInteger("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", 16)
    private val pA = "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470" +
        "f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c"
    private val pB = "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676" +
        "d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7"

    private fun a() = Spake2(Spake2.Role.A, "server".toByteArray(), "client".toByteArray(), w, x)
    private fun b() = Spake2(Spake2.Role.B, "server".toByteArray(), "client".toByteArray(), w, y)

    @Test
    fun `the RFC 9382 vector reproduces messages, keys, and both confirmations`() {
        a().message.toHex() shouldBe pA
        b().message.toHex() shouldBe pB
        val ka = a().finish(Spake2.hex(pB))
        val kb = b().finish(Spake2.hex(pA))
        ka.ke.toHex() shouldBe "0e0672dc86f8e45565d338b0540abe69"
        ka.confirmA.toHex() shouldBe "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0"
        ka.confirmB.toHex() shouldBe "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b"
        kb.confirmA.toHex() shouldBe ka.confirmA.toHex()
        kb.confirmB.toHex() shouldBe ka.confirmB.toHex()
    }

    @Test
    fun `a malformed or off-curve message is refused`() {
        assertThrows<IllegalArgumentException> { a().finish(ByteArray(65) { 4 }) }
        assertThrows<IllegalArgumentException> { a().finish(ByteArray(3)) }
    }

    @Test
    fun `the same PIN pairs, a different PIN does not`() {
        val joiner = Pairing.joiner("482913", "fp-joiner", "fp-coordinator")
        val good = Pairing.coordinator("482913", "fp-joiner", "fp-coordinator")
        val aad = Pairing.aad("session-1")
        joiner.finish(good.message, aad).confirmB.toHex() shouldBe good.finish(joiner.message, aad).confirmB.toHex()

        val joiner2 = Pairing.joiner("482913", "fp-joiner", "fp-coordinator")
        val wrongPin = Pairing.coordinator("000000", "fp-joiner", "fp-coordinator")
        joiner2.finish(wrongPin.message, aad).confirmB.toHex() shouldNotBe wrongPin.finish(joiner2.message, aad).confirmB.toHex()
    }

    @Test
    fun `a device relaying between the two TVs cannot make the confirmations agree`() {
        // The joiner saw the relay's certificate; the coordinator knows its own. Same PIN, messages passed through.
        val joiner = Pairing.joiner("482913", "fp-joiner", "fp-relay")
        val coordinator = Pairing.coordinator("482913", "fp-joiner", "fp-coordinator")
        val aad = Pairing.aad("session-1")
        joiner.finish(coordinator.message, aad).confirmB.toHex() shouldNotBe coordinator.finish(joiner.message, aad).confirmB.toHex()
    }

    @Test
    fun `a confirmation belongs to one session`() {
        val joiner = Pairing.joiner("482913", "j", "c")
        val coordinator = Pairing.coordinator("482913", "j", "c")
        joiner.finish(coordinator.message, Pairing.aad("s1")).confirmA.toHex() shouldNotBe
            coordinator.finish(joiner.message, Pairing.aad("s2")).confirmA.toHex()
    }
}
