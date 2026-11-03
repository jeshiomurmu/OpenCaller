import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension
import io.github.jeshiomurmu.opencaller.buildlogic.library
import io.github.jeshiomurmu.opencaller.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * `opencaller.android.room`: Room with KSP and exported schemas in `schemas/` (needed for migration tests). The schema
 * location is passed to KSP as the `room.schemaLocation` option, the way Room documents without its Gradle plugin:
 * with AGP's built-in Kotlin the plugin never handed its schema arguments to KSP, so no schema was written
 * (docs/phase.md issue 102). The androidTest assets include the schemas for `MigrationTestHelper`.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")
            val schemas = File(projectDir, "schemas")
            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
                arg(RoomSchemaArgProvider(schemas))
            }
            extensions.configure<LibraryExtension> {
                sourceSets.getByName("androidTest").assets.srcDir(schemas.path)
            }
            dependencies {
                "implementation"(libs.library("androidx-room-runtime"))
                "ksp"(libs.library("androidx-room-compiler"))
            }
        }
    }
}

/** Passes [schemaDir] to Room's KSP processor and tells Gradle the folder is an input (Room's documented provider). */
class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
}
