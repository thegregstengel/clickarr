package net.clickarr.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TitleSortTest {
    @Test
    fun `articles are ignored and case does not matter`() {
        listOf("The Office", "archer", "A Christmas Story", "Bob's Burgers", "An American Tail", "the a-team")
            .sortedBy(::titleSortKey) shouldBe
            listOf("the a-team", "An American Tail", "archer", "Bob's Burgers", "A Christmas Story", "The Office")
    }

    @Test
    fun `a title that is only an article keeps its word`() {
        titleSortKey("The") shouldBe "the"
    }
}
