plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
