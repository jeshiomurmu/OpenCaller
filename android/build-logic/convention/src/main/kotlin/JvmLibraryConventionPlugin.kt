import com.android.build.api.dsl.Lint
import io.github.jeshiomurmu.opencaller.buildlogic.configureKotlinJvm
import io.github.jeshiomurmu.opencaller.buildlogic.configureOpenCallerLint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/** `opencaller.jvm.library`: pure Kotlin/JVM modules (`core:model`, `core:common`) with no Android dependency. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "com.android.lint")
            configureKotlinJvm()
            extensions.configure<Lint> { configureOpenCallerLint(target) }
        }
    }
}
