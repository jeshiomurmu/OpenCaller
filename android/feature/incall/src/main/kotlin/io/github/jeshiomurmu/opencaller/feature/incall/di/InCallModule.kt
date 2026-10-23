package io.github.jeshiomurmu.opencaller.feature.incall.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallRegistry
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallerCache
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallerDirectory

/** The calls of the in-call service, for the in-call screen and the notification actions. */
@Module
@InstallIn(SingletonComponent::class)
internal interface InCallModule {
    @Binds
    fun callStateRepository(impl: CallRegistry): CallStateRepository

    @Binds
    fun callController(impl: CallRegistry): CallController

    @Binds
    fun callerDirectory(impl: CallerCache): CallerDirectory
}
