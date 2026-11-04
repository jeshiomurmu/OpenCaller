package io.github.jeshiomurmu.opencaller.feature.social.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashAlerts
import io.github.jeshiomurmu.opencaller.feature.social.FlashNotifier

/** Flash messages show as notifications. */
@Module
@InstallIn(SingletonComponent::class)
interface SocialModule {
    /** The notifier. */
    @Binds
    fun flashAlerts(impl: FlashNotifier): FlashAlerts
}
