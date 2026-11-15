package io.github.jeshiomurmu.opencaller.feature.scanner.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallScanner
import io.github.jeshiomurmu.opencaller.feature.scanner.LineScanner

/** The voice scan for the in-call screen. */
@Module
@InstallIn(SingletonComponent::class)
internal interface ScannerFeatureModule {
    @Binds
    fun callScanner(impl: LineScanner): CallScanner
}
