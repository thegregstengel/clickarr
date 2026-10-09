import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Adds Compose, Compose for TV, and the compose compiler to an Android module. Apply after an android plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val android = extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>
            android.buildFeatures.compose = true
            dependencies {
                val bom = libs.findLibrary("androidx.compose.bom").get()
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.findLibrary("androidx.compose.foundation").get())
                add("implementation", libs.findLibrary("androidx.compose.ui").get())
                add("implementation", libs.findLibrary("androidx.compose.ui.tooling.preview").get())
                add("implementation", libs.findLibrary("androidx.tv.material").get())
                add("implementation", libs.findLibrary("androidx.lifecycle.runtime.compose").get())
                add("debugImplementation", libs.findLibrary("androidx.compose.ui.tooling").get())
                add("debugImplementation", libs.findLibrary("androidx.compose.ui.test.manifest").get())
                add("androidTestImplementation", libs.findLibrary("androidx.compose.ui.test.junit4").get())
            }
        }
    }
}
