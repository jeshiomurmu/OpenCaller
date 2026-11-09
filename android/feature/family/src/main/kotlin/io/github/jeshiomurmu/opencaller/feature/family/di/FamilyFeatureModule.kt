package io.github.jeshiomurmu.opencaller.feature.family.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyAlertNotices
import io.github.jeshiomurmu.opencaller.feature.family.FamilyAlertNotifier

/** The admin's alert notifications. */
@Module
@InstallIn(SingletonComponent::class)
internal interface FamilyFeatureModule {
    @Binds
    fun familyAlertNotices(impl: FamilyAlertNotifier): FamilyAlertNotices
}
