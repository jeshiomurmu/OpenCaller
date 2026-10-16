package io.github.jeshiomurmu.opencaller.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Enables Jetpack Compose for an Android module and adds the Compose BOM and tooling. */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }
    }
    dependencies {
        val bom = libs.library("androidx-compose-bom")
        "implementation"(platform(bom))
        "implementation"(libs.library("androidx-compose-ui-tooling-preview"))
        "debugImplementation"(libs.library("androidx-compose-ui-tooling"))
    }
}
