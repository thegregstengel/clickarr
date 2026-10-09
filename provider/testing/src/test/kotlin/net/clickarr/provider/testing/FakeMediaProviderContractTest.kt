package net.clickarr.provider.testing

import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import org.junit.jupiter.api.Test

class FakeMediaProviderContractTest : MediaProviderContractTest() {
    override fun provider(): MediaProvider = FakeMediaProvider()

    @Test
    fun `library filter by decade and genre narrows the result`() = runTest {
        val p = FakeMediaProvider()
        val movieLib = p.libraries().value()[1].ref
        val eighties = p.resolve(ProgrammingSource.Library(movieLib, MediaFilter(decadeStart = 1980))).value()
        eighties.map { it.ref.id.value }.sorted() shouldBe listOf("mv-bttf", "mv-goonies")
        val sciFiEighties = MediaFilter(genres = setOf("Sci-Fi"), decadeStart = 1980)
        val eightiesComedy = p.resolve(ProgrammingSource.Library(movieLib, sciFiEighties)).value()
        eightiesComedy.map { it.ref.id.value } shouldBe listOf("mv-bttf")
    }

    @Test
    fun `failures propagate as typed errors`() = runTest {
        val p = FakeMediaProvider(failWith = ClickarrError.Unauthorized())
        (p.libraries() is Outcome.Failure) shouldBe true
        (p.playbackSource(p.library.movies.first().ref, DeviceProfile.Conservative, 1.minutes) is Outcome.Failure) shouldBe true
    }
}
