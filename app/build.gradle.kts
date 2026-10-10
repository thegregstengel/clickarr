plugins {
    alias(libs.plugins.clickarr.android.application)
    alias(libs.plugins.clickarr.android.compose)
    alias(libs.plugins.clickarr.hilt)
}

android {
    namespace = "net.clickarr"

    defaultConfig {
        applicationId = "net.clickarr"
        versionCode = 1
        versionName = "0.0.1-phase0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing is configured by CI from secrets (docs/release.md). Local release builds are unsigned.
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
