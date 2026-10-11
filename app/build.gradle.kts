plugins {
    alias(libs.plugins.clickarr.android.application)
    alias(libs.plugins.clickarr.android.compose)
    alias(libs.plugins.clickarr.hilt)
}

android {
    namespace = "net.clickarr"

    defaultConfig {
        applicationId = "net.clickarr"
        // CI sets CLICKARR_VERSION_CODE to the minutes since 2026-01-01 so every build is a newer version, and
        // CLICKARR_VERSION_NAME to a calendar version (docs/release.md): "2026.10.11" for a release from its tag,
        // "2026.10.11-nightly.407561" for a nightly. Nothing in source names a version; a local build is "dev".
        versionCode = System.getenv("CLICKARR_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("CLICKARR_VERSION_NAME")?.ifBlank { null } ?: "dev"
        // Google OAuth client for Drive sync (ADR 0020); empty in builds without one, and the Sync pane says so.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${System.getenv("CLICKARR_GOOGLE_CLIENT_ID").orEmpty()}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${System.getenv("CLICKARR_GOOGLE_CLIENT_SECRET").orEmpty()}\"")
    }

    // Release signing comes from the environment (docs/release.md). Without it, release builds stay unsigned.
    val keystorePath = System.getenv("CLICKARR_KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CLICKARR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLICKARR_KEY_ALIAS")
                keyPassword = System.getenv("CLICKARR_KEY_PASSWORD")
            }
        }
    }

    // Nightly (debug) builds sign with one long-lived key held by CI (docs/release.md), so a nightly can
    // update over the previous one. Without it the debug key is whatever the build machine generated, and
    // a device refuses the update as signed by a stranger.
    val nightlyKeystorePath = System.getenv("CLICKARR_NIGHTLY_KEYSTORE_PATH")
    if (!nightlyKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("nightly") {
                storeFile = file(nightlyKeystorePath)
                storePassword = System.getenv("CLICKARR_NIGHTLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLICKARR_NIGHTLY_KEY_ALIAS")?.ifBlank { null } ?: "clickarr-nightly"
                keyPassword = System.getenv("CLICKARR_NIGHTLY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (!nightlyKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("nightly")
                // A published nightly must not be debuggable: run-as and JDWP would read the token store.
                isDebuggable = false
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:scheduling"))
    implementation(project(":core:database"))
    implementation(project(":core:secrets"))
    implementation(project(":provider:api"))
    implementation(project(":provider:plex"))
    implementation(project(":playback:core"))
    implementation(project(":playback:media3"))
    implementation(project(":data"))
    implementation(project(":ui:design"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:player"))
    implementation(project(":feature:channels"))
    implementation(project(":feature:guide"))
    implementation(project(":feature:settings"))
    implementation(project(":spike"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(project(":provider:plex-fixtures"))
    androidTestImplementation(project(":household:coordinator"))
    androidTestImplementation(libs.ktor.server.cio)
}
