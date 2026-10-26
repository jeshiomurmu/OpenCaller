package io.github.jeshiomurmu.opencaller.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.BuildConfig
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import javax.inject.Singleton

/** The server this build talks to, from BuildConfig (Gradle properties; see app/build.gradle.kts). */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    /** No URL = no server: blocking works offline and online features say "Not connected to a server". */
    @Provides
    @Singleton
    fun serverConfig(): ServerConfig =
        serverConfigOf(
            baseUrl = BuildConfig.API_BASE_URL,
            pins = BuildConfig.API_PINS,
            flavor = BuildConfig.FLAVOR,
            versionName = BuildConfig.VERSION_NAME,
        )
}

/** Builds the config; pins apply to the play flavor only (foss uses system trust, docs/rules.md §7). */
internal fun serverConfigOf(
    baseUrl: String,
    pins: String,
    flavor: String,
    versionName: String,
): ServerConfig =
    ServerConfig(
        baseUrl = baseUrl.trim().ifEmpty { null },
        pins = if (flavor == PLAY_FLAVOR) pins.split(',').map(String::trim).filter { it.isNotEmpty() } else emptyList(),
        userAgent = "OpenCaller/$versionName ($flavor)",
        flavor = flavor,
    )

private const val PLAY_FLAVOR = "play"
