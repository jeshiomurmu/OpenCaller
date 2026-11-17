import com.android.build.api.dsl.TestExtension
import io.github.jeshiomurmu.opencaller.buildlogic.AndroidSdk
import io.github.jeshiomurmu.opencaller.buildlogic.configureKotlinAndroid
import io.github.jeshiomurmu.opencaller.buildlogic.configureOpenCallerLint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/** `opencaller.android.test`: `com.android.test` modules that test the app from outside (`:benchmark`). */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.test")
            extensions.configure<TestExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = AndroidSdk.TARGET_SDK
                lint { configureOpenCallerLint(target) }
            }
        }
    }
}
