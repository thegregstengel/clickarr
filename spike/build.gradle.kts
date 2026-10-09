// Phase 0 spikes. This module is deleted once the spikes have produced their go/no-go notes (docs/spikes.md).
plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.clickarr.android.compose)
}

android {
    namespace = "net.clickarr.spike"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":ui:design"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Spike B: playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)

    // Spike C: TLS termination (platform SSLServerSocket), Ktor CIO for plain HTTP, OkHttp as the pinned client.
    // Netty and the Ktor client are excluded on purpose: they need API 26 (see SslHttpServer).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.okhttp)
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
}
