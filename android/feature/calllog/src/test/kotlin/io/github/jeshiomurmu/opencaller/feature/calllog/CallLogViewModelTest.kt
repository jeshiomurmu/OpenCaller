package io.github.jeshiomurmu.opencaller.feature.calllog

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.providers.CallLogSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** TC-LOG-001 through the call log screen's state: filters, search, grouping, names, and the row actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLogViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    // Today at noon, so the calls a few minutes apart share a day.
    private val now =
        LocalDate
            .now()
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    private val minute = 60_000L
    private val ram = PhoneNumber("+12015550111")
    private val spammer = PhoneNumber("+12025550199")

    private val calls =
        MutableStateFlow<AppResult<List<CallLogEntry>>>(
            AppResult.Success(
                listOf(
                    CallLogEntry(3, ram, ram.e164, null, CallType.MISSED, now - minute, 0),
                    CallLogEntry(2, spammer, spammer.e164, null, CallType.BLOCKED, now - 2 * minute, 0),
                    CallLogEntry(1, ram, ram.e164, null, CallType.INCOMING, now - 3 * minute, 40),
                ),
            ),
        )
    private val events =
        MutableStateFlow(
            listOf(
                ScreeningEvent(
                    1,
                    spammer,
                    Direction.INCOMING,
                    Decision.BLOCK,
                    ScreeningReason.SPAM_LIST,
                    0.9f,
                    CallerVerification.UNKNOWN,
                    5,
                    now - 2 * minute,
                ),
            ),
        )
    private var canRead = true
    private val deleted = mutableListOf<Collection<Long>>()
    private val blocked = mutableListOf<PhoneNumber>()
    private val undone = mutableListOf<Long>()
    private var blockResult: AppResult<Long> = AppResult.Success(77)
    private val placer = RecordingPlacer()

    private val callLog =
        object : CallLogSource {
            override fun canRead() = canRead

            override fun observe(): Flow<AppResult<List<CallLogEntry>>> = calls

            override suspend fun delete(ids: Collection<Long>): AppResult<Int> = AppResult.Success(ids.size).also { deleted += ids }
        }

    private val contacts =
        object : ContactsSource {
            override fun canRead() = true

            override fun canWrite() = true

            override fun observeAll(): Flow<AppResult<List<ContactSummary>>> =
                flowOf(
                    AppResult.Success(
                        listOf(ContactSummary(5, "k", "Ram Kumar", false, listOf(ContactPhone(ram, "(201) 555-0111", PhoneLabel.MOBILE)))),
                    ),
                )

            override fun observe(contactId: Long): Flow<AppResult<ContactDetail?>> = flowOf(AppResult.Success(null))

            override suspend fun save(
                rawContactId: Long?,
                draft: ContactDraft,
            ): AppResult<Long> = AppResult.Failure(AppError.Storage)

            override suspend fun setStarred(
                contactId: Long,
                starred: Boolean,
            ): AppResult<Unit> = AppResult.Success(Unit)
        }

    private val blocker =
        object : NumberBlocker {
            override suspend fun block(number: PhoneNumber): AppResult<Long> = blockResult.also { blocked += number }

            override suspend fun undo(ruleId: Long): AppResult<Unit> = AppResult.Success(Unit).also { undone += ruleId }
        }

    private lateinit var vm: CallLogViewModel

    private fun TestScope.start() {
        vm =
            CallLogViewModel(
                inputs = CallLogInputs(callLog, contacts) { events },
                blocker = blocker,
                placer = placer,
                numbers = NumberDisplay(PhoneNumberNormalizer()) { "US" },
                clock = FakeClock(now),
                dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun rows() =
        vm.uiState.value.sections
            .flatMap { it.rows }

    @Test
    fun tcLog001CallsAreGroupedNamedAndFiltered() =
        runTest {
            start()
            val state = vm.uiState.value
            assertEquals(CallLogAccess.READY, state.access)
            assertEquals(1, state.sections.size)
            val (ramRow, spamRow) = rows()
            assertEquals("Ram Kumar", ramRow.name)
            assertEquals(5L, ramRow.contactId)
            assertEquals(listOf(3L, 1L), ramRow.ids)
            assertEquals(2, ramRow.count)
            assertEquals(CallType.MISSED, ramRow.type)
            assertEquals("(201) 555-0111", ramRow.display)
            assertEquals(CallFlags(blocked = true, spam = true, spamScore = 0.9f), spamRow.flags)

            vm.onEvent(CallLogEvent.SetFilter(CallFilter.MISSED))
            assertEquals(listOf(listOf(3L)), rows().map { it.ids })
            vm.onEvent(CallLogEvent.SetFilter(CallFilter.BLOCKED))
            assertEquals(listOf(listOf(2L)), rows().map { it.ids })
            vm.onEvent(CallLogEvent.SetFilter(CallFilter.SPAM))
            assertEquals(listOf(listOf(2L)), rows().map { it.ids })
            vm.onEvent(CallLogEvent.SetFilter(CallFilter.ALL))
            vm.onEvent(CallLogEvent.Search("kumar"))
            assertEquals(listOf(listOf(3L, 1L)), rows().map { it.ids })
        }

    @Test
    fun theLogUpdatesWhenAndroidsLogChanges() =
        runTest {
            start()
            calls.value = AppResult.Success(emptyList())
            assertTrue(
                vm.uiState.value.sections
                    .isEmpty(),
            )
        }

    @Test
    fun withoutThePermissionTheScreenAsksAndReadsAgainAfterwards() =
        runTest {
            canRead = false
            start()
            assertEquals(CallLogAccess.NEEDS_PERMISSION, vm.uiState.value.access)
            canRead = true
            vm.onEvent(CallLogEvent.Reload)
            assertEquals(CallLogAccess.READY, vm.uiState.value.access)
        }

    @Test
    fun callBackDialsOrOpensTheDialPadForThePermission() =
        runTest {
            start()
            val row = rows().first()
            vm.onEvent(CallLogEvent.Call(row))
            assertEquals(listOf(ram.e164), placer.placed)

            placer.permitted = false
            vm.onEvent(CallLogEvent.Call(row))
            assertEquals(CallLogEffect.OpenDialPad(ram.e164), vm.effects.first())
            assertEquals(1, placer.placed.size)
        }

    @Test
    fun blockCanBeUndone() =
        runTest {
            start()
            vm.onEvent(CallLogEvent.Block(rows().first()))
            assertEquals(listOf(ram), blocked)
            assertEquals(CallLogNotice.Blocked("+1 ••• 0111", 77), vm.uiState.value.notice)
            vm.onEvent(CallLogEvent.UndoBlock)
            assertEquals(listOf(77L), undone)
            assertNull(vm.uiState.value.notice)

            blockResult = AppResult.Failure(AppError.Storage)
            vm.onEvent(CallLogEvent.Block(rows().first()))
            assertEquals(CallLogNotice.Failed, vm.uiState.value.notice)
        }

    @Test
    fun deleteAsksFirst() =
        runTest {
            start()
            val row = rows().first()
            vm.onEvent(CallLogEvent.AskDelete(row))
            assertEquals(row, vm.uiState.value.confirmDelete)
            vm.onEvent(CallLogEvent.CancelDelete)
            assertTrue(deleted.isEmpty())

            vm.onEvent(CallLogEvent.AskDelete(row))
            vm.onEvent(CallLogEvent.ConfirmDelete)
            assertEquals(listOf<Collection<Long>>(listOf(3L, 1L)), deleted)
            assertNull(vm.uiState.value.confirmDelete)
        }
}

/** Records the numbers that would have been dialled. */
private class RecordingPlacer : CallPlacer {
    var permitted = true
    val placed = mutableListOf<String>()

    override fun canPlaceCalls() = permitted

    override fun isEmergencyNumber(number: String) = number == "911"

    override fun place(
        number: String,
        simId: String?,
    ): AppResult<Unit> = AppResult.Success(Unit).also { placed += number }

    override fun placeEmergency(number: String): AppResult<Unit> = place(number)

    override fun placeVoicemail(simId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override fun sendSecretCode(digits: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun handOff(number: String): AppResult<Unit> = AppResult.Success(Unit)
}
