package io.github.jeshiomurmu.opencaller.feature.voip.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallHandler
import io.github.jeshiomurmu.opencaller.feature.voip.AppCallController

/** This build has app calls: pushes for calls go to the controller. */
@Module
@InstallIn(SingletonComponent::class)
interface VoipCallModule {
    /** The controller handles call pushes. */
    @Binds
    fun appCallHandler(impl: AppCallController): AppCallHandler
}
