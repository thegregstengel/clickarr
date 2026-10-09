plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
}
