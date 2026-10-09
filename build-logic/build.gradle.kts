plugins {
    `kotlin-dsl`
}

group = "net.clickarr.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "clickarr.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "clickarr.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "clickarr.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("kotlinJvm") {
            id = "clickarr.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
        register("hilt") {
            id = "clickarr.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
