plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:scheduling"))
    api(project(":provider:api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":provider:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
