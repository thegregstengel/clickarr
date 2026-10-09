// Application services shared by feature modules: repositories, registry, device preferences.
plugins {
    alias(libs.plugins.clickarr.android.library)
    alias(libs.plugins.clickarr.hilt)
}

android {
    namespace = "net.clickarr.data"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:scheduling"))
    api(project(":core:database"))
    api(project(":core:secrets"))
    api(project(":provider:api"))
    api(project(":provider:plex"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
}
