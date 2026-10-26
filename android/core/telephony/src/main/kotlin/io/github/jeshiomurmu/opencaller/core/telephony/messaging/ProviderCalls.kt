package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.ContentUris
import android.database.sqlite.SQLiteException
import android.net.Uri
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import timber.log.Timber

/**
 * Runs a call into Android's message store and maps its failures to [AppError]s at the boundary (docs/rules.md §5):
 * a missing permission or role → [AppError.PermissionDenied], anything the provider refuses → [AppError.Storage].
 */
internal inline fun <T> providerCall(
    what: String,
    block: () -> T,
): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: SecurityException) {
        Timber.w(e, "Message store %s not allowed", what)
        AppResult.Failure(AppError.PermissionDenied)
    } catch (e: SQLiteException) {
        Timber.w(e, "Message store %s failed", what)
        AppResult.Failure(AppError.Storage)
    } catch (e: IllegalArgumentException) {
        // Also NumberFormatException (a returned row without an id) and "Unable to find or allocate a thread ID".
        Timber.w(e, "Message store %s rejected", what)
        AppResult.Failure(AppError.Storage)
    } catch (e: IllegalStateException) {
        Timber.w(e, "Message store %s refused", what)
        AppResult.Failure(AppError.Storage)
    } catch (e: UnsupportedOperationException) {
        Timber.w(e, "Message store %s returned an unexpected address", what)
        AppResult.Failure(AppError.Storage)
    }

/** The row id of an inserted [uri]; refuses a missing one. */
internal fun Uri?.insertedId(what: String): Long = ContentUris.parseId(this ?: throw StoreRefusedException(what))
