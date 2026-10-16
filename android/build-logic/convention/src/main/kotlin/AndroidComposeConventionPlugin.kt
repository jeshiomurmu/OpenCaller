import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.github.jeshiomurmu.opencaller.buildlogic.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

/** `opencaller.android.compose`: enables Compose on an application or library module. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")
            // These callbacks run once the Android plugin has registered its `android` extension.
            pluginManager.withPlugin("com.android.application") {
                configureAndroidCompose(extensions.getByType<ApplicationExtension>())
            }
            pluginManager.withPlugin("com.android.library") {
                configureAndroidCompose(extensions.getByType<LibraryExtension>())
            }
        }
    }
}
