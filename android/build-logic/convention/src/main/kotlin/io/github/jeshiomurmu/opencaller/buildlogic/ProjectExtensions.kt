package io.github.jeshiomurmu.opencaller.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The `libs` version catalog (`gradle/libs.versions.toml`), for use inside convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Looks up a library alias in the version catalog, failing with a clear message if it is missing. */
internal fun VersionCatalog.library(alias: String) =
    findLibrary(alias).orElseThrow { IllegalStateException("Library '$alias' is missing from gradle/libs.versions.toml") }
