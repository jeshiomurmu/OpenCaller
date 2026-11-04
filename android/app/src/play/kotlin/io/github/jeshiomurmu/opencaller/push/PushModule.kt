package io.github.jeshiomurmu.opencaller.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The play build gets pushes through FCM. */
@Module
@InstallIn(SingletonComponent::class)
interface PushModule {
    /** FCM. */
    @Binds
    fun pushSetup(impl: FcmPushSetup): PushSetup
}
