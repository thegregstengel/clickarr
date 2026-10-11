// Wire types and pure logic for the Clickarr Household (ADR 0011, 0013). No Android, no I/O.
plugins {
    alias(libs.plugins.clickarr.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:scheduling"))
    api(libs.kotlinx.serialization.json)
    // Elliptic-curve arithmetic for SPAKE2 pairing (ADR 0013, 2026-10-11 amendment); used directly, not as a JCA provider.
    implementation(libs.bouncycastle.prov)
}
