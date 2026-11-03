package io.github.jeshiomurmu.opencaller.feature.business.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.business.ClaimFileReader
import io.github.jeshiomurmu.opencaller.feature.business.DeviceClaimFiles

/** The claim flow's file reader. */
@Module
@InstallIn(SingletonComponent::class)
internal interface BusinessModule {
    @Binds
    fun claimFiles(impl: DeviceClaimFiles): ClaimFileReader
}
