package io.github.jeshiomurmu.opencaller.core.telephony.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.provider.CallLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's call log. It is read and changed on the device only and never uploaded (docs/architecture.md §10), and
 * only while OpenCaller is the default phone app: Play policy allows the call log permissions to the default phone
 * app only (docs/architecture.md §5).
 */
interface CallLogSource {
    /** True when OpenCaller is the default phone app and may read the call log. */
    fun canRead(): Boolean

    /** The newest calls (up to a few hundred), newest first, again after every change to the log. */
    fun observe(): Flow<AppResult<List<CallLogEntry>>>

    /** Deletes the calls with [ids]; returns how many were deleted. */
    suspend fun delete(ids: Collection<Long>): AppResult<Int>
}

/** Whether OpenCaller is the default phone app right now (`RoleManager.ROLE_DIALER`). */
fun interface DefaultPhoneAppRole {
    /** True while OpenCaller holds the role. */
    fun isHeld(): Boolean
}

/** [CallLogSource] backed by `CallLog.Calls`. */
@Singleton
class DeviceCallLog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val phoneApp: DefaultPhoneAppRole,
        private val normalizer: PhoneNumberNormalizer,
        private val region: HomeRegionProvider,
        private val dispatchers: DispatcherProvider,
    ) : CallLogSource {
        override fun canRead(): Boolean = isDefaultPhoneApp() && granted(Manifest.permission.READ_CALL_LOG)

        override fun observe(): Flow<AppResult<List<CallLogEntry>>> =
            context.contentResolver
                .changes(CallLog.Calls.CONTENT_URI)
                .map { read() }
                .flowOn(dispatchers.io)

        // Checked just above the provider call (role + WRITE_CALL_LOG); a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        override suspend fun delete(ids: Collection<Long>): AppResult<Int> =
            when {
                ids.isEmpty() -> AppResult.Success(0)
                !isDefaultPhoneApp() || !granted(Manifest.permission.WRITE_CALL_LOG) -> AppResult.Failure(AppError.PermissionDenied)
                else -> withContext(dispatchers.io) { guarded("delete") { ids.chunked(MAX_ARGS).sumOf(::deleteRows) } }
            }

        @SuppressLint("MissingPermission")
        private fun deleteRows(ids: List<Long>): Int {
            val placeholders = ids.joinToString(",") { "?" }
            return context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} IN ($placeholders)",
                ids.map(Long::toString).toTypedArray(),
            )
        }

        // canRead() checks the role and READ_CALL_LOG first; a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        private fun read(): AppResult<List<CallLogEntry>> {
            if (!canRead()) return AppResult.Failure(AppError.PermissionDenied)
            val uri =
                CallLog.Calls.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, LIMIT.toString())
                    .build()
            val home = region.homeRegion() ?: UNKNOWN_REGION
            return guarded("read") {
                context.contentResolver
                    .query(uri, PROJECTION, null, null, CallLog.Calls.DEFAULT_SORT_ORDER)
                    ?.use { cursor -> buildList { while (cursor.moveToNext()) add(entryOf(cursor, home)) } }
                    .orEmpty()
            }
        }

        private fun entryOf(
            cursor: Cursor,
            home: String,
        ): CallLogEntry {
            val presentation = cursor.getInt(COLUMN_PRESENTATION)
            val raw = if (presentation == CallLog.Calls.PRESENTATION_ALLOWED) cursor.getString(COLUMN_NUMBER).orEmpty() else ""
            return CallLogEntry(
                id = cursor.getLong(COLUMN_ID),
                number = raw.takeIf(String::isNotBlank)?.let { normalizer.fromHandle(it, home) },
                dialString = raw,
                cachedName = cursor.getString(COLUMN_NAME)?.takeIf(String::isNotBlank),
                type = callTypeOf(cursor.getInt(COLUMN_TYPE)),
                atMillis = cursor.getLong(COLUMN_DATE),
                durationSeconds = cursor.getLong(COLUMN_DURATION),
                simId = cursor.getString(COLUMN_ACCOUNT_ID),
            )
        }

        private fun isDefaultPhoneApp(): Boolean = phoneApp.isHeld()

        private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        // Errors at the provider boundary become AppErrors (docs/rules.md §5).
        private fun <T> guarded(
            what: String,
            block: () -> T,
        ): AppResult<T> =
            try {
                AppResult.Success(block())
            } catch (e: SecurityException) {
                Timber.w(e, "Call log %s not allowed", what)
                AppResult.Failure(AppError.PermissionDenied)
            } catch (e: SQLiteException) {
                Timber.w(e, "Call log %s failed", what)
                AppResult.Failure(AppError.Storage)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Call log %s rejected", what)
                AppResult.Failure(AppError.Storage)
            }

        /** Limits of the reader. */
        companion object {
            /** How many calls are read: the newest ones. */
            const val LIMIT = 500

            private const val MAX_ARGS = 500
            private const val UNKNOWN_REGION = "ZZ"
            private const val COLUMN_ID = 0
            private const val COLUMN_NUMBER = 1
            private const val COLUMN_NAME = 2
            private const val COLUMN_TYPE = 3
            private const val COLUMN_DATE = 4
            private const val COLUMN_DURATION = 5
            private const val COLUMN_ACCOUNT_ID = 6
            private const val COLUMN_PRESENTATION = 7

            private val PROJECTION =
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.NUMBER_PRESENTATION,
                )
        }
    }

/** `CallLog.Calls.TYPE` to [CallType]. */
internal fun callTypeOf(type: Int): CallType =
    when (type) {
        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
        CallLog.Calls.VOICEMAIL_TYPE -> CallType.VOICEMAIL
        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.ANSWERED_ELSEWHERE
        else -> CallType.UNKNOWN
    }
