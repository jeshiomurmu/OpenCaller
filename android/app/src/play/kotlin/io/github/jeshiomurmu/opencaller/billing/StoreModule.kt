package io.github.jeshiomurmu.opencaller.billing

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterStore

/** The play build buys the supporter subscription through Google Play Billing. */
@Module
@InstallIn(SingletonComponent::class)
interface StoreModule {
    /** Play Billing. */
    @Binds
    fun store(impl: PlaySupporterStore): SupporterStore
}
