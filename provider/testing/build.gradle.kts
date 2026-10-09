plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
}

dependencies {
    api(project(":provider:api"))
    // The contract suite is shipped in main so provider modules can extend it from their own tests.
    implementation(platform(libs.junit5.bom))
    implementation(libs.junit5.jupiter)
    implementation(libs.kotest.assertions)
    implementation(libs.kotlinx.coroutines.test)
}
