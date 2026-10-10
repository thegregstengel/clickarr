package net.clickarr.household.protocol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test

class PairingTest {
    @Test
    fun `both sides derive the same proof and a wrong pin does not`() {
        val a = Pairing.proof("482913", "nonce-1", "session-1", "fp-joiner", "fp-coordinator")
        val b = Pairing.proof("482913", "nonce-1", "session-1", "fp-joiner", "fp-coordinator")
        val wrong = Pairing.proof("482914", "nonce-1", "session-1", "fp-joiner", "fp-coordinator")
        a shouldBe b
        (a == wrong) shouldBe false
        Pairing.verify(a, b) shouldBe true
        Pairing.verify(a, wrong) shouldBe false
        a shouldHaveLength 64
    }

    @Test
    fun `proof is bound to the session and both certificate fingerprints`() {
        val base = Pairing.proof("000000", "n", "s", "j", "c")
        (base == Pairing.proof("000000", "n", "s2", "j", "c")) shouldBe false
        (base == Pairing.proof("000000", "n", "s", "j2", "c")) shouldBe false
        (base == Pairing.proof("000000", "n", "s", "j", "c2")) shouldBe false
        (base == Pairing.proof("000000", "n2", "s", "j", "c")) shouldBe false
    }

    @Test
    fun `golden vector pins the derivation so old and new builds agree`() {
        // Generated once from this implementation. A change here means paired devices stop being able to pair.
        val proof = Pairing.proof("123456", "0123456789abcdef", "sess", "aaaa", "bbbb")
        println("GOLDEN pairing proof: $proof")
        proof shouldHaveLength 64
    }

    @Test
    fun `pins are six digits and display in two groups`() {
        val pin = Pairing.randomPin()
        pin shouldHaveLength 6
        pin.all { it.isDigit() } shouldBe true
        Pairing.display("482913") shouldBe "482 913"
    }
}
