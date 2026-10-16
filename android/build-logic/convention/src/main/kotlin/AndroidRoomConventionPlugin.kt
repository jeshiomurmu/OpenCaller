import androidx.room.gradle.RoomExtension
import com.google.devtools.ksp.gradle.KspExtension
import io.github.jeshiomurmu.opencaller.buildlogic.library
import io.github.jeshiomurmu.opencaller.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** `opencaller.android.room`: Room with KSP and exported schemas (needed for migration tests). */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "androidx.room")
            apply(plugin = "com.google.devtools.ksp")
            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
            }
            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }
            dependencies {
                "implementation"(libs.library("androidx-room-runtime"))
                "ksp"(libs.library("androidx-room-compiler"))
            }
        }
    }
}
