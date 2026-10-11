package net.clickarr.household.protocol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test

class PairingTest {
    @Test
    fun `constant-time comparison matches equal strings only`() {
        Pairing.verify("abc123", "abc123") shouldBe true
        Pairing.verify("abc123", "abc124") shouldBe false
        Pairing.verify("abc", "abcd") shouldBe false
    }

    @Test
    fun `pins are six digits and display in two groups`() {
        val pin = Pairing.randomPin()
        pin shouldHaveLength 6
        pin.all { it.isDigit() } shouldBe true
        Pairing.display("482913") shouldBe "482 913"
    }
}
