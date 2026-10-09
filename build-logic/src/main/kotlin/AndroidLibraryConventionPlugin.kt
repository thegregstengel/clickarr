import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
                testOptions.targetSdk = ProjectConfig.TARGET_SDK
            }
            dependencies {
                add("testImplementation", libs.findLibrary("junit5.jupiter").get())
                add("testImplementation", libs.findLibrary("kotest.assertions").get())
                add("testRuntimeOnly", libs.findLibrary("junit5.platform.launcher").get())
                add("testImplementation", platform(libs.findLibrary("junit5.bom").get()))
            }
            tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                useJUnitPlatform()
            }
        }
    }
}
