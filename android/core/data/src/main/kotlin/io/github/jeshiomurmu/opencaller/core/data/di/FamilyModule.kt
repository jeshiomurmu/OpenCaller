package io.github.jeshiomurmu.opencaller.core.data.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyAlertReporter
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyGroups
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyPreferences
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyRepository
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyWork
import io.github.jeshiomurmu.opencaller.core.data.family.OtpWarningReport
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeed
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeedCache
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeedRepository
import io.github.jeshiomurmu.opencaller.core.data.family.WorkManagerFamilyWork
import io.github.jeshiomurmu.opencaller.core.network.FamilyApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import java.io.File
import javax.inject.Singleton

/** Phase 12: family protection and the scam feed (ADR 0008). */
@Module
@InstallIn(SingletonComponent::class)
interface FamilyModule {
    /** Alerts and daily counts on WorkManager. */
    @Binds
    fun familyWork(impl: WorkManagerFamilyWork): FamilyWork

    /** Family groups for the screens. */
    @Binds
    fun familyGroups(impl: FamilyRepository): FamilyGroups

    /** Scam cards for the Protection tab. */
    @Binds
    fun scamFeedSource(impl: ScamFeedRepository): ScamFeed

    /** The OTP guard's warnings, sent to the admin only while the member shares them. */
    @Binds
    fun otpWarningReport(impl: FamilyAlertReporter): OtpWarningReport

    /** Providers. */
    companion object {
        /** The Phase 12 API. */
        @Provides
        fun familyApi(network: OpenCallerNetwork): FamilyApi = network.familyApi

        /** This phone's own membership. */
        @Provides
        @Singleton
        fun familyPreferences(
            @ApplicationContext context: Context,
        ): FamilyPreferences =
            FamilyPreferences(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FamilyPreferences.FILE_NAME) })

        /** The scam feed with its offline copy (app files, so it survives a cache clear). */
        @Provides
        @Singleton
        fun scamFeed(
            @ApplicationContext context: Context,
            api: FamilyApi,
            dispatchers: DispatcherProvider,
        ): ScamFeedRepository = ScamFeedRepository(api, ScamFeedCache(File(context.filesDir, "scam-feed")), dispatchers)
    }
}
