// Android-only household plumbing: device identity in the Keystore and LAN discovery with NsdManager (ADR 0012, 0013).
plugins {
    alias(libs.plugins.clickarr.android.library)
}

android {
    namespace = "net.clickarr.household.discovery"
}

dependencies {
    api(project(":household:protocol"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}
