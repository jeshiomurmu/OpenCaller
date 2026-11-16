package io.github.jeshiomurmu.opencaller.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Configures SDK levels, Java and Kotlin options for an Android module (AGP built-in Kotlin). */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = AndroidSdk.COMPILE_SDK
        defaultConfig.apply {
            minSdk = AndroidSdk.MIN_SDK
        }
        compileOptions.apply {
            sourceCompatibility = AndroidSdk.JAVA_VERSION
            targetCompatibility = AndroidSdk.JAVA_VERSION
        }
    }
    configureKotlin<KotlinAndroidProjectExtension>()
    configureTestLogging()
}

/** Configures Java and Kotlin options for a pure JVM module. */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = AndroidSdk.JAVA_VERSION
        targetCompatibility = AndroidSdk.JAVA_VERSION
    }
    configureKotlin<KotlinJvmProjectExtension>()
    configureTestLogging()
}

/** A failing test prints its message and stack trace in the build log (CI keeps no test reports). */
private fun Project.configureTestLogging() {
    tasks.withType<Test>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

private inline fun <reified T : KotlinBaseExtension> Project.configureKotlin() =
    configure<T> {
        val compilerOptions =
            when (this) {
                is KotlinAndroidProjectExtension -> compilerOptions
                is KotlinJvmProjectExtension -> compilerOptions
                else -> error("Unsupported Kotlin extension ${T::class}")
            }
        compilerOptions.apply {
            jvmTarget.set(JvmTarget.JVM_17)
            // docs/rules.md §4: zero warnings in new code.
            allWarningsAsErrors.set(true)
        }
    }
