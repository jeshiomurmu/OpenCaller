package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs database work for a repository: on the I/O dispatcher (opening the encrypted database reads the Keystore
 * and disk), with every failure mapped to [AppError.Storage] and logged (docs/rules.md §5).
 */
internal class Storage(
    private val access: DatabaseAccess,
    private val dispatchers: DispatcherProvider,
) {
    // Room, SQLCipher and the Keystore throw many unrelated exception types; all of them mean "storage failed".

    /** Runs [block] against the database. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> run(block: suspend OpenCallerDatabase.() -> T): AppResult<T> =
        try {
            AppResult.Success(withContext(dispatchers.io) { access.database().block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Database operation failed")
            AppResult.Failure(AppError.Storage)
        }

    /** Observes [query]; a failure ends the flow with one [AppError.Storage]. */
    fun <T> observe(query: OpenCallerDatabase.() -> Flow<T>): Flow<AppResult<T>> =
        flow { emitAll(access.database().query()) }
            .map<T, AppResult<T>> { AppResult.Success(it) }
            .catch { e ->
                if (e is CancellationException) throw e
                Timber.w(e, "Database query failed")
                emit(AppResult.Failure(AppError.Storage))
            }.flowOn(dispatchers.io)
}
