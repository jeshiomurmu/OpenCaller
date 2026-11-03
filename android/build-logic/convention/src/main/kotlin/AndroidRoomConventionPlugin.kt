import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension
import io.github.jeshiomurmu.opencaller.buildlogic.library
import io.github.jeshiomurmu.opencaller.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import java.io.File

/**
 * `opencaller.android.room`: Room with KSP and exported schemas in `schemas/` (needed for migration tests). The schema
 * location is passed to KSP as the plain `room.schemaLocation` option. With AGP's built-in Kotlin, KSP 2.3 ignores
 * `CommandLineArgumentProvider` arguments (the Room Gradle plugin's route, and Room's documented provider): no schema
 * was written and the option didn't even change KSP's cache key (docs/phase.md issue 102). A plain option isn't tracked
 * as an input folder, so CI re-runs KSP without incremental processing to check the schemas. The androidTest assets
 * include the schemas for `MigrationTestHelper`.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")
            val schemas = File(projectDir, "schemas")
            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
                arg("room.schemaLocation", schemas.path)
            }
            extensions.configure<LibraryExtension> {
                sourceSets
                    .getByName("androidTest")
                    .assets.directories
                    .add(schemas.path)
            }
            dependencies {
                "implementation"(libs.library("androidx-room-runtime"))
                "ksp"(libs.library("androidx-room-compiler"))
            }
        }
    }
}
