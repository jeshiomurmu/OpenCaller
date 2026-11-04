package io.github.jeshiomurmu.opencaller.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The foss build gets pushes through a UnifiedPush distributor. */
@Module
@InstallIn(SingletonComponent::class)
interface PushModule {
    /** UnifiedPush. */
    @Binds
    fun pushSetup(impl: UnifiedPushSetup): PushSetup
}
