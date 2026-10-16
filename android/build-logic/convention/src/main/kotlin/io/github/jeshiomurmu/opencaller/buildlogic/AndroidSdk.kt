package io.github.jeshiomurmu.opencaller.buildlogic

import org.gradle.api.JavaVersion

/** SDK levels and Java version shared by every Android module (docs/architecture.md §2). */
internal object AndroidSdk {
    /** API 29 is the first level with `RoleManager.ROLE_CALL_SCREENING`. */
    const val MIN_SDK = 29
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
}
