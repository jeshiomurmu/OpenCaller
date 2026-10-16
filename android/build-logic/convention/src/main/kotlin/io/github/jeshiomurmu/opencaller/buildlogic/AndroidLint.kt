package io.github.jeshiomurmu.opencaller.buildlogic

import com.android.build.api.dsl.Lint
import org.gradle.api.Project

/**
 * Shared Android Lint settings: every warning fails the build. Pre-existing issues can be
 * accepted per module by committing a `lint-baseline.xml` next to the module's build file
 * (`./gradlew :module:updateLintBaseline` refreshes it), so only new code must be clean.
 */
internal fun Lint.configureOpenCallerLint(project: Project) {
    abortOnError = true
    warningsAsErrors = true
    // Dependency and SDK freshness is handled by Renovate/Dependabot and the targetSdk is fixed by
    // docs/architecture.md §2, so "newer version available" checks must not break the build.
    disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    val baselineFile = project.file("lint-baseline.xml")
    if (baselineFile.exists()) {
        baseline = baselineFile
    }
}
