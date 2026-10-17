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
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.data.account.Account
import io.github.jeshiomurmu.opencaller.core.data.account.AccountRepository
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.account.KeystoreTokenStore
import io.github.jeshiomurmu.opencaller.core.data.lookup.NumberSearch
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuotaStore
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchRepository
import io.github.jeshiomurmu.opencaller.core.data.sync.DefaultSpamListUpdates
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncStateStore
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListUpdates
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.data.work.WorkManagerBackgroundWork
import io.github.jeshiomurmu.opencaller.core.database.crypto.AndroidKeystoreCipher
import io.github.jeshiomurmu.opencaller.core.network.LookupApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import javax.inject.Singleton

/**
 * The server API and the stores around it. [ServerConfig] (base URL, pins, flavor) comes from the app's
 * BuildConfig; everything else is built here. One DataStore per file per process.
 */
@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
    /** WorkManager-backed background jobs. */
    @Binds
    fun backgroundWork(impl: WorkManagerBackgroundWork): BackgroundWork

    /** Sign-in. */
    @Binds
    fun account(impl: AccountRepository): Account

    /** Sign-in status. */
    @Binds
    fun accountStatus(impl: AccountRepository): AccountStatus

    /** Number search and disputes. */
    @Binds
    fun numberSearch(impl: SearchRepository): NumberSearch

    /** Spam-list update status and "Update now". */
    @Binds
    fun spamListUpdates(impl: DefaultSpamListUpdates): SpamListUpdates

    /** The encrypted token store is the API's token store. */
    @Binds
    fun tokenStore(impl: KeystoreTokenStore): TokenStore

    /** Providers. */
    companion object {
        /** Tokens encrypted with their own Keystore key. */
        @Provides
        @Singleton
        fun keystoreTokenStore(
            @ApplicationContext context: Context,
        ): KeystoreTokenStore =
            KeystoreTokenStore(
                dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(KeystoreTokenStore.FILE_NAME) },
                cipher = AndroidKeystoreCipher(KeystoreTokenStore.KEY_ALIAS),
            )

        /** Clients and APIs for the configured server. */
        @Provides
        @Singleton
        fun network(
            config: ServerConfig,
            tokens: TokenStore,
            clock: Clock,
        ): OpenCallerNetwork = OpenCallerNetwork(config, tokens, clock)

        /** The main API. */
        @Provides
        fun api(network: OpenCallerNetwork): OpenCallerApi = network.api

        /** The live-lookup API (800 ms limit). */
        @Provides
        fun lookupApi(network: OpenCallerNetwork): LookupApi = network.lookupApi

        /** Spam-list version on this phone. */
        @Provides
        @Singleton
        fun syncStateStore(
            @ApplicationContext context: Context,
        ): SpamListSyncStateStore =
            SpamListSyncStateStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SpamListSyncStateStore.FILE_NAME) })

        /** Search quota bookkeeping. */
        @Provides
        @Singleton
        fun searchQuotaStore(
            @ApplicationContext context: Context,
        ): SearchQuotaStore =
            SearchQuotaStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SearchQuotaStore.FILE_NAME) })
    }
}
