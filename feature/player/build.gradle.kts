plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.clickarr.android.compose)
    alias(libs.plugins.clickarr.hilt)
}

android {
    namespace = "net.clickarr.feature.player"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:scheduling"))
    implementation(project(":data"))
    implementation(project(":playback:core"))
    implementation(project(":playback:media3"))
    implementation(project(":ui:design"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
}
