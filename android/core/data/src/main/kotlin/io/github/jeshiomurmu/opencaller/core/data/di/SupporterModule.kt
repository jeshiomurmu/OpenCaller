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
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementStore
import io.github.jeshiomurmu.opencaller.core.data.supporter.ServerEntitlements
import io.github.jeshiomurmu.opencaller.core.data.sync.ExtendedListStateStore
import io.github.jeshiomurmu.opencaller.core.network.BillingApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import java.io.File
import javax.inject.Singleton

/**
 * Supporter tier (Phase 10): entitlements from the server and the extended spam list. The store side
 * ([io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterStore]) is bound per flavor in `app/src/<flavor>`.
 */
@Module
@InstallIn(SingletonComponent::class)
interface SupporterModule {
    /** Entitlements come from the server. */
    @Binds
    fun entitlements(impl: ServerEntitlements): EntitlementProvider

    /** Providers. */
    companion object {
        /** The billing API of the configured server. */
        @Provides
        fun billingApi(network: OpenCallerNetwork): BillingApi = network.billingApi

        /** The server's last answer, in the no-backup directory (a restored backup never carries an entitlement). */
        @Provides
        @Singleton
        fun entitlementStore(
            @ApplicationContext context: Context,
        ): EntitlementStore =
            EntitlementStore(
                PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "${EntitlementStore.FILE_NAME}.preferences_pb") },
            )

        /** Which extended list the phone has. */
        @Provides
        @Singleton
        fun extendedListStateStore(
            @ApplicationContext context: Context,
        ): ExtendedListStateStore =
            ExtendedListStateStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(ExtendedListStateStore.FILE_NAME) })
    }
}
