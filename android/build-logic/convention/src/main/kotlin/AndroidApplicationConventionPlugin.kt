import com.android.build.api.dsl.ApplicationExtension
import io.github.jeshiomurmu.opencaller.buildlogic.AndroidSdk
import io.github.jeshiomurmu.opencaller.buildlogic.configureKotlinAndroid
import io.github.jeshiomurmu.opencaller.buildlogic.configureOpenCallerLint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/** `opencaller.android.application`: the `app` module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = AndroidSdk.TARGET_SDK
                lint { configureOpenCallerLint(target) }
            }
        }
    }
}
