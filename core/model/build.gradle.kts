plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
}
