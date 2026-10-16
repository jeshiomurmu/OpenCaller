import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/**
 * `opencaller.android.feature`: an Android library with Compose and Hilt that depends on the UI core
 * modules. Enforces docs/architecture.md §3: a feature module never depends on another feature module.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "opencaller.android.library")
            apply(plugin = "opencaller.android.compose")
            apply(plugin = "opencaller.android.hilt")
            dependencies {
                "implementation"(project(":core:designsystem"))
                "implementation"(project(":core:ui"))
            }
            val modulePath = path
            configurations.configureEach {
                withDependencies {
                    filterIsInstance<ProjectDependency>()
                        .firstOrNull { it.path.startsWith(":feature:") }
                        ?.let { throw GradleException("$modulePath must not depend on ${it.path}: features depend on core modules only") }
                }
            }
        }
    }
}
