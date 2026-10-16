package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import io.github.jeshiomurmu.opencaller.core.database.crypto.DatabasePassphraseStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opens the production database once, on first use, with the Keystore-protected passphrase. Callers use
 * [database] from a background coroutine; repositories map its exceptions to `AppError.Storage`.
 */
class EncryptedDatabaseProvider(
    private val context: Context,
    private val passphraseStore: DatabasePassphraseStore,
) {
    private val mutex = Mutex()

    @Volatile
    private var instance: OpenCallerDatabase? = null

    /** The database, opened on first call. */
    suspend fun database(): OpenCallerDatabase =
        instance ?: mutex.withLock {
            instance ?: OpenCallerDatabaseFactory
                .create(context, passphraseStore.getOrCreate())
                .also { instance = it }
        }
}
