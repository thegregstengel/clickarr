// Fake Plex server built on MockWebServer and the JSON fixtures. Used by provider:plex unit tests and by
// the app's instrumented UI tests on the emulator. Not shipped in the APK.
plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
}

dependencies {
    api(libs.okhttp.mockwebserver)
    implementation(libs.kotlinx.serialization.json)
}
