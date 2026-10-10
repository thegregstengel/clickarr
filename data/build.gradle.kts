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
    api(project(":household:protocol"))
    api(project(":household:coordinator"))
    api(project(":household:client"))
    api(project(":household:discovery"))
    implementation(libs.ktor.server.cio)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.androidx.room.ktx)
    implementation("org.slf4j:slf4j-simple:2.0.17")
    api(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
}
