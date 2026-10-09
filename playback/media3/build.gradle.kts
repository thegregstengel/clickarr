plugins {
    alias(libs.plugins.clickarr.android.library)
}

android {
    namespace = "net.clickarr.playback.media3"
}

dependencies {
    api(project(":playback:core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
}
