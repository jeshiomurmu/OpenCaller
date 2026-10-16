import io.github.jeshiomurmu.opencaller.buildlogic.library
import io.github.jeshiomurmu.opencaller.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** `opencaller.android.hilt`: Hilt with KSP on an Android module. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("com.android.base") {
                apply(plugin = "com.google.devtools.ksp")
                apply(plugin = "com.google.dagger.hilt.android")
                dependencies {
                    "implementation"(libs.library("hilt-android"))
                    "ksp"(libs.library("hilt-compiler"))
                    // Lets Hilt read metadata written by the project's (newer) Kotlin compiler.
                    "ksp"(libs.library("kotlin-metadata-jvm"))
                }
            }
        }
    }
}
