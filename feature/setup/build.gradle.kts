plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.clickarr.android.compose)
    alias(libs.plugins.clickarr.hilt)
}

android {
    namespace = "net.clickarr.feature.setup"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":provider:api"))
    implementation(project(":provider:plex"))
    implementation(project(":ui:design"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
}
