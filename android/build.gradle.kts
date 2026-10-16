import io.gitlab.arturbosch.detekt.Detekt

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// One Spotless and one Detekt run cover every module (and build-logic), so flavor and
// test source sets are never missed.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(file("config/detekt/detekt.yml"))
    source.setFrom(files("app/src", "core", "feature", "build-logic/convention/src"))
}

tasks.withType<Detekt>().configureEach {
    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
}
