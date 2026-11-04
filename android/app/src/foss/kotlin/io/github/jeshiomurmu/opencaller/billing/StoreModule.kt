package io.github.jeshiomurmu.opencaller.billing

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.BuildConfig
import io.github.jeshiomurmu.opencaller.core.data.supporter.NoSupporterStore
import io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterStore

/**
 * The foss build has no store (no Google Play Services): supporter features unlock on a self-hosted server, and the
 * supporter screen links to donations (`opencaller.donationUrl`).
 */
@Module
@InstallIn(SingletonComponent::class)
object StoreModule {
    /** No store; the donation link, if the build has one. */
    @Provides
    fun store(): SupporterStore = NoSupporterStore(BuildConfig.DONATION_URL.ifBlank { null })
}
