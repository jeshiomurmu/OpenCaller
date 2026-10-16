package io.github.jeshiomurmu.opencaller.core.telephony.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceAccessStatusSource

/** Binds the telephony interfaces to their device implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal interface TelephonyModule {
    @Binds
    fun accessStatusSource(impl: DeviceAccessStatusSource): AccessStatusSource
}
