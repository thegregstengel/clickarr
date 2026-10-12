package net.clickarr.core.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class RedactTest {
    @Test
    fun `plex token in query string is redacted`() {
        val out = Redact.apply("GET http://plex.local:32400/library/parts/1/file.mkv?X-Plex-Token=abc123XYZ&other=1")
        out shouldNotContain "abc123XYZ"
        out shouldBe "GET http://plex.local:32400/library/parts/1/file.mkv?X-Plex-Token=<redacted>&other=1"
    }

    @Test
    fun `api_key and bearer tokens are redacted`() {
        Redact.apply("http://jf/Videos/1/stream?static=true&api_key=deadbeef") shouldNotContain "deadbeef"
        Redact.apply("Authorization: Bearer eyJhbGciOi.payload.sig") shouldNotContain "eyJhbGciOi"
        Redact.apply("X-Plex-Token: 5f6a") shouldNotContain "5f6a"
    }

    @Test
    fun `the token in a Jellyfin MediaBrowser authorization is redacted`() {
        val header = "MediaBrowser Client=\"Clickarr\", Device=\"TV\", DeviceId=\"d1\", Version=\"1\", Token=\"abc123def\""
        val out = Redact.apply(header)
        out shouldNotContain "abc123def"
        out shouldContain "DeviceId=\"d1\""
        Redact.apply("http://jf/videos/1/master.m3u8?PlaySessionId=p&ApiKey=abc123def") shouldNotContain "abc123def"
    }

    @Test
    fun `ordinary text is untouched`() {
        Redact.apply("Channel 10 Sitcoms, The Office at 7:00 PM") shouldBe "Channel 10 Sitcoms, The Office at 7:00 PM"
    }
}
