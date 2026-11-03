package io.github.jeshiomurmu.opencaller.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.FileGhostCallPhotos
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallPhotos
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCalls
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.WorkManagerGhostCalls

/** Ghost Call's scheduler and photo store. */
@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsModule {
    @Binds
    fun ghostCalls(impl: WorkManagerGhostCalls): GhostCalls

    @Binds
    fun ghostCallPhotos(impl: FileGhostCallPhotos): GhostCallPhotos
}
