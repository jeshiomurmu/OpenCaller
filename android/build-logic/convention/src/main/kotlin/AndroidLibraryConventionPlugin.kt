import com.android.build.api.dsl.LibraryExtension
import io.github.jeshiomurmu.opencaller.buildlogic.AndroidSdk
import io.github.jeshiomurmu.opencaller.buildlogic.configureKotlinAndroid
import io.github.jeshiomurmu.opencaller.buildlogic.configureOpenCallerLint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/** `opencaller.android.library`: Android `core:*` modules and the base of every feature module. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = AndroidSdk.TARGET_SDK
                lint.targetSdk = AndroidSdk.TARGET_SDK
                lint { configureOpenCallerLint(target) }
            }
        }
    }
}
