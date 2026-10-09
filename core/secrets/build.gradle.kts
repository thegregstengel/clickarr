plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.clickarr.core.secrets"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
