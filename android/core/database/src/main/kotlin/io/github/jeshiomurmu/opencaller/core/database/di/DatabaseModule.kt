package io.github.jeshiomurmu.opencaller.core.database.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.EncryptedDatabaseProvider
import io.github.jeshiomurmu.opencaller.core.database.crypto.AndroidKeystoreCipher
import io.github.jeshiomurmu.opencaller.core.database.crypto.DatabasePassphraseStore
import javax.inject.Singleton

/** Provides the encrypted database. One DataStore per file and one database per process. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val KEY_STORE_FILE = "database_key"

    /** Keystore-wrapped passphrase storage. */
    @Provides
    @Singleton
    fun passphraseStore(
        @ApplicationContext context: Context,
    ): DatabasePassphraseStore =
        DatabasePassphraseStore(
            dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(KEY_STORE_FILE) },
            cipher = AndroidKeystoreCipher(),
        )

    /** Lazily opened encrypted database. */
    @Provides
    @Singleton
    fun databaseProvider(
        @ApplicationContext context: Context,
        passphraseStore: DatabasePassphraseStore,
    ): EncryptedDatabaseProvider = EncryptedDatabaseProvider(context, passphraseStore)

    /** What repositories use: the encrypted database. */
    @Provides
    fun databaseAccess(provider: EncryptedDatabaseProvider): DatabaseAccess = provider
}
