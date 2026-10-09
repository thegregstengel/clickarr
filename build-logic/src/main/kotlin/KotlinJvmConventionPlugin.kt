import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Pure Kotlin module: no Android dependency, JUnit 5 + Kotest for tests.
 * The scheduler, domain model, and household protocol use this so they run on the JVM in milliseconds.
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            }
            configureKotlinJvmTarget()
            dependencies {
                add("testImplementation", platform(libs.findLibrary("junit5.bom").get()))
                add("testImplementation", libs.findLibrary("junit5.jupiter").get())
                add("testImplementation", libs.findLibrary("kotest.assertions").get())
                add("testImplementation", libs.findLibrary("kotest.property").get())
                add("testRuntimeOnly", libs.findLibrary("junit5.platform.launcher").get())
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
        }
    }
}
