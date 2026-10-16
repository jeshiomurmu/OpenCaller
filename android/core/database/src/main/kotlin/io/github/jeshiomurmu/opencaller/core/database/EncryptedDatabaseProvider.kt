package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import io.github.jeshiomurmu.opencaller.core.database.crypto.DatabasePassphraseStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Gives access to the app database; repositories depend on this so tests can pass an unencrypted in-memory database. */
fun interface DatabaseAccess {
    /** The database, opened on first call. Call from a background coroutine. */
    suspend fun database(): OpenCallerDatabase
}

/**
 * Opens the production database once, on first use, with the Keystore-protected passphrase. Callers use
 * [database] from a background coroutine; repositories map its exceptions to `AppError.Storage`.
 */
class EncryptedDatabaseProvider(
    private val context: Context,
    private val passphraseStore: DatabasePassphraseStore,
) : DatabaseAccess {
    private val mutex = Mutex()

    @Volatile
    private var instance: OpenCallerDatabase? = null

    override suspend fun database(): OpenCallerDatabase =
        instance ?: mutex.withLock {
            instance ?: OpenCallerDatabaseFactory
                .create(context, passphraseStore.getOrCreate())
                .also { instance = it }
        }
}
