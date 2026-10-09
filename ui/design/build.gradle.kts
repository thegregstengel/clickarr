plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.clickarr.android.compose)
}

android {
    namespace = "net.clickarr.ui.design"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
