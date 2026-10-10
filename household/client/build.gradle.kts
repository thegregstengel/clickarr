// Member side of the household protocol: talks to a coordinator over HTTP(S) with OkHttp. Pure Kotlin.
plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":household:protocol"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":household:coordinator"))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.kotlinx.coroutines.test)
}
