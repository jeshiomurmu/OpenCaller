package io.github.jeshiomurmu.opencaller.core.telephony.providers

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Reading and deleting Android's call log (TC-LOG-001's data), only as the default phone app. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class DeviceCallLogTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCallLogProvider
    private var defaultPhoneApp = true

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCallLogProvider::class.java, CallLog.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        provider.rows +=
            listOf(
                row(1, "+12025550143", CallLog.Calls.INCOMING_TYPE, name = "Alex", account = "sim-1"),
                row(2, "2025550199", CallLog.Calls.MISSED_TYPE),
                row(3, "", CallLog.Calls.MISSED_TYPE, presentation = CallLog.Calls.PRESENTATION_RESTRICTED),
                row(4, "*123#", CallLog.Calls.OUTGOING_TYPE),
                row(5, "+12025550199", CallLog.Calls.BLOCKED_TYPE),
                row(6, "+12025550150", CallLog.Calls.REJECTED_TYPE),
                row(7, "+12025550151", 99),
            )
    }

    private fun callLog(dispatcher: UnconfinedTestDispatcher) =
        DeviceCallLog(app, { defaultPhoneApp }, PhoneNumberNormalizer(), { "US" }, TestDispatcherProvider(dispatcher))

    @Test
    fun callsAreReadNewestFirstWithTheirNumbers() =
        runTest {
            val calls = (callLog(UnconfinedTestDispatcher(testScheduler)).observe().first() as AppResult.Success).value
            assertEquals((1L..7L).toList(), calls.map(CallLogEntry::id))
            assertEquals(
                CallLogEntry(1, PhoneNumber("+12025550143"), "+12025550143", "Alex", CallType.INCOMING, 7_000, 60, "sim-1"),
                calls[0],
            )
            // A national number from the log is read with the home region.
            assertEquals(PhoneNumber("+12025550199"), calls[1].number)
            assertEquals(
                listOf(
                    CallType.MISSED,
                    CallType.MISSED,
                    CallType.OUTGOING,
                    CallType.BLOCKED,
                    CallType.REJECTED,
                    CallType.UNKNOWN,
                ),
                calls.drop(1).map {
                    it.type
                },
            )
            assertEquals(CallLog.Calls.DEFAULT_SORT_ORDER, provider.lastSortOrder)
            assertEquals(DeviceCallLog.LIMIT.toString(), provider.lastUri?.getQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY))
        }

    @Test
    fun hiddenCallersAndCodesHaveNoNumber() =
        runTest {
            val calls = (callLog(UnconfinedTestDispatcher(testScheduler)).observe().first() as AppResult.Success).value
            val hidden = calls[2]
            assertNull(hidden.number)
            assertEquals(true, hidden.isHidden)
            assertNull(hidden.callBackString)
            val code = calls[3]
            assertNull(code.number)
            assertEquals("*123#", code.callBackString)
        }

    @Test
    fun onlyTheDefaultPhoneAppReadsTheLog() =
        runTest {
            defaultPhoneApp = false
            assertEquals(AppResult.Failure(AppError.PermissionDenied), callLog(UnconfinedTestDispatcher(testScheduler)).observe().first())
            assertEquals(0, provider.queries)

            defaultPhoneApp = true
            shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
            assertEquals(AppResult.Failure(AppError.PermissionDenied), callLog(UnconfinedTestDispatcher(testScheduler)).observe().first())
            assertEquals(0, provider.queries)
        }

    @Test
    fun deleteRemovesTheGivenCalls() =
        runTest {
            val log = callLog(UnconfinedTestDispatcher(testScheduler))
            assertEquals(AppResult.Success(2), log.delete(listOf(2L, 4L)))
            assertEquals(listOf(1L, 3L, 5L, 6L, 7L), provider.rows.map { it[0] as Long })
            assertEquals(AppResult.Success(0), log.delete(emptyList()))

            defaultPhoneApp = false
            assertEquals(AppResult.Failure(AppError.PermissionDenied), log.delete(listOf(1L)))
            assertEquals(5, provider.rows.size)
        }

    private fun row(
        id: Long,
        number: String,
        type: Int,
        name: String? = null,
        account: String? = null,
        presentation: Int = CallLog.Calls.PRESENTATION_ALLOWED,
    ): Array<Any?> = arrayOf(id, number, name, type, 8_000 - id * 1_000, 60L, account, presentation)
}

/** A call log provider holding [rows] in [COLUMNS] order. */
class FakeCallLogProvider : ContentProvider() {
    val rows = mutableListOf<Array<Any?>>()
    var lastUri: Uri? = null
    var lastSortOrder: String? = null
    var queries = 0

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries++
        lastUri = uri
        lastSortOrder = sortOrder
        val columns = projection ?: COLUMNS
        val cursor = MatrixCursor(columns)
        rows.forEach { row -> cursor.addRow(columns.map { row[COLUMNS.indexOf(it)] }) }
        return cursor
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ids = selectionArgs.orEmpty().map(String::toLong).toSet()
        val before = rows.size
        rows.removeAll { it[0] as Long in ids }
        return before - rows.size
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val COLUMNS =
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
