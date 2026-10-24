package io.github.jeshiomurmu.opencaller.feature.contacts

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.ContactPhoneDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The contacts list, detail and editor (docs/prd.md F-CM-04): list, detail, create/edit, favourites. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ram = PhoneNumber("+12015550111")
    private val store = FakeContactStore()

    private fun TestScope.dispatchers() = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.list(): ContactsListViewModel =
        ContactsListViewModel(store, dispatchers()).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    @Test
    fun theListIsByLetterWithFavouritesFirst() =
        runTest {
            store.add("Émile Zola", starred = false)
            store.add("ram kumar", starred = true, number = ram)
            store.add("Rita", starred = false)
            store.add("123 Taxi", starred = false)
            val state = list().uiState.value
            assertEquals(ContactsAccess.READY, state.access)
            assertEquals(listOf("ram kumar"), state.favourites.map { it.name })
            assertEquals(listOf("E", "R", "#"), state.sections.map { it.letter })
            assertEquals(listOf("ram kumar", "Rita"), state.sections[1].contacts.map { it.name })
        }

    @Test
    fun searchMatchesNamesAndNumbers() =
        runTest {
            store.add("Ram Kumar", starred = true, number = ram)
            store.add("Rita", starred = false)
            val vm = list()
            vm.onEvent(ContactsListEvent.Search("555 0111"))
            assertEquals(
                listOf("Ram Kumar"),
                vm.uiState.value.sections
                    .flatMap { it.contacts }
                    .map { it.name },
            )
            assertTrue(
                "no favourites row while searching",
                vm.uiState.value.favourites
                    .isEmpty(),
            )
            vm.onEvent(ContactsListEvent.Search("RIT"))
            assertEquals(
                listOf("Rita"),
                vm.uiState.value.sections
                    .flatMap { it.contacts }
                    .map { it.name },
            )
        }

    @Test
    fun withoutThePermissionTheListAsksAndReadsAgainAfterwards() =
        runTest {
            store.readable = false
            val vm = list()
            assertEquals(ContactsAccess.NEEDS_PERMISSION, vm.uiState.value.access)
            store.readable = true
            vm.onEvent(ContactsListEvent.Reload)
            assertEquals(ContactsAccess.READY, vm.uiState.value.access)
        }

    @Test
    fun letters() {
        assertEquals("E", ContactIndex.letterOf("  émile"))
        assertEquals("O", ContactIndex.letterOf("Öz"))
        assertEquals("#", ContactIndex.letterOf("+880 1712"))
        assertEquals("#", ContactIndex.letterOf(""))
    }

    @Test
    fun theDetailShowsNumbersWithWhatTheDeviceKnows() =
        runTest {
            val id = store.add("Ram Kumar", starred = false, number = ram)
            val verdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "loan", 12)
            val vm = detail { CallerInfo(number = ram, spamVerdict = verdict) }
            vm.onEvent(ContactDetailEvent.Open(id))
            val state = vm.uiState.value
            assertEquals("Ram Kumar", state.contact?.name)
            assertEquals("(201) 555-0111", state.numbers.single().display)
            assertEquals(
                verdict,
                state.numbers
                    .single()
                    .info
                    ?.spamVerdict,
            )

            store.contacts.value = emptyList()
            assertTrue("a deleted contact closes the screen", vm.uiState.value.gone)
        }

    @Test
    fun favouritesNeedTheWritePermissionFirst() =
        runTest {
            val id = store.add("Ram Kumar", starred = false, number = ram)
            store.writable = false
            val vm = detail()
            vm.onEvent(ContactDetailEvent.Open(id))
            vm.onEvent(ContactDetailEvent.ToggleFavourite)
            assertTrue(vm.uiState.value.explainingPermission)
            assertFalse(
                store.contacts.value
                    .single()
                    .starred,
            )

            store.writable = true
            vm.onEvent(ContactDetailEvent.PermissionResult(granted = true))
            assertFalse(vm.uiState.value.explainingPermission)
            assertTrue(
                vm.uiState.value.contact
                    ?.starred == true,
            )
        }

    @Test
    fun callingFromTheDetailDialsOrOpensTheDialPad() =
        runTest {
            val id = store.add("Ram Kumar", starred = false, number = ram)
            val placer = Placer()
            val vm = detail(placer = placer)
            vm.onEvent(ContactDetailEvent.Open(id))
            vm.onEvent(ContactDetailEvent.Call(ram.e164))
            assertEquals(listOf(ram.e164), placer.placed)
            placer.permitted = false
            vm.onEvent(ContactDetailEvent.Call(ram.e164))
            assertEquals(ContactDetailEffect.OpenDialPad(ram.e164), vm.effects.first())
        }

    @Test
    fun aNewContactStartsWithTheGivenNumberAndSaves() =
        runTest {
            val vm = ContactEditViewModel(store)
            vm.onEvent(ContactEditEvent.Prepare(contactId = null, number = "+12015550199"))
            assertTrue(vm.uiState.value.isNew)
            assertEquals(listOf(ContactPhoneDraft("+12015550199")), vm.uiState.value.draft.phones)

            vm.onEvent(ContactEditEvent.Name("Sam"))
            vm.onEvent(ContactEditEvent.AddPhone)
            vm.onEvent(ContactEditEvent.Phone(1, "029876543"))
            vm.onEvent(ContactEditEvent.Label(1, PhoneLabel.WORK))
            vm.onEvent(ContactEditEvent.Email(0, "sam@example.com"))
            vm.onEvent(ContactEditEvent.Save)
            val saved = store.saved.single()
            assertNull(saved.first)
            assertEquals(
                ContactDraft(
                    "Sam",
                    listOf(ContactPhoneDraft("+12015550199"), ContactPhoneDraft("029876543", PhoneLabel.WORK)),
                    listOf("sam@example.com"),
                ),
                saved.second,
            )
            assertEquals(
                store.contacts.value
                    .last()
                    .id,
                vm.uiState.value.savedContactId,
            )
        }

    @Test
    fun editingLoadsTheContactAndWritesItsRawContact() =
        runTest {
            val id = store.add("Ram Kumar", starred = false, number = ram)
            val vm = ContactEditViewModel(store)
            vm.onEvent(ContactEditEvent.Prepare(contactId = id, number = null))
            assertFalse(vm.uiState.value.isNew)
            assertEquals("Ram Kumar", vm.uiState.value.draft.name)
            vm.onEvent(ContactEditEvent.RemovePhone(0))
            vm.onEvent(ContactEditEvent.Save)
            assertEquals(id * 10, store.saved.single().first)
            assertEquals(id, vm.uiState.value.savedContactId)
        }

    @Test
    fun emptyContactsAndRefusedPermissionsAreExplained() =
        runTest {
            val vm = ContactEditViewModel(store)
            vm.onEvent(ContactEditEvent.Prepare(null, null))
            vm.onEvent(ContactEditEvent.Save)
            assertEquals(ContactEditProblem.EMPTY, vm.uiState.value.problem)

            store.writable = false
            vm.onEvent(ContactEditEvent.Name("Sam"))
            vm.onEvent(ContactEditEvent.Save)
            assertTrue(vm.uiState.value.explainingPermission)
            vm.onEvent(ContactEditEvent.PermissionResult(granted = false))
            assertEquals(ContactEditProblem.NO_PERMISSION, vm.uiState.value.problem)
            assertTrue(store.saved.isEmpty())

            store.writable = true
            store.saveResult = AppResult.Failure(AppError.Storage)
            vm.onEvent(ContactEditEvent.Save)
            assertEquals(ContactEditProblem.FAILED, vm.uiState.value.problem)
        }

    private fun TestScope.detail(
        placer: CallPlacer = Placer(),
        insights: NumberInsights = NumberInsights { null },
    ): ContactDetailViewModel =
        ContactDetailViewModel(store, insights, placer, NumberDisplay(PhoneNumberNormalizer()) { "US" }, dispatchers()).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }
}

/** Contacts held in memory; a contact's raw contact id is its id × 10. */
private class FakeContactStore : ContactsSource {
    val contacts = MutableStateFlow<List<ContactSummary>>(emptyList())
    var readable = true
    var writable = true
    var saveResult: AppResult<Long>? = null
    val saved = mutableListOf<Pair<Long?, ContactDraft>>()

    fun add(
        name: String,
        starred: Boolean,
        number: PhoneNumber? = null,
    ): Long {
        val id = contacts.value.size + 1L
        val phones = listOfNotNull(number?.let { ContactPhone(it, it.e164, PhoneLabel.MOBILE) })
        contacts.value = (contacts.value + ContactSummary(id, "key$id", name, starred, phones)).sortedBy { it.name.lowercase() }
        return id
    }

    override fun canRead() = readable

    override fun canWrite() = writable

    override fun observeAll(): Flow<AppResult<List<ContactSummary>>> = contacts.map { AppResult.Success(it) }

    override fun observe(contactId: Long): Flow<AppResult<ContactDetail?>> =
        contacts.map { list ->
            AppResult.Success(
                list.firstOrNull { it.id == contactId }?.let {
                    ContactDetail(
                        it.id,
                        it.lookupKey,
                        it.name,
                        it.starred,
                        it.phones,
                        emptyList(),
                        it.id * 10,
                    )
                },
            )
        }

    override suspend fun save(
        rawContactId: Long?,
        draft: ContactDraft,
    ): AppResult<Long> {
        saveResult?.let { return it }
        saved += rawContactId to draft
        return AppResult.Success(rawContactId?.div(10) ?: add(draft.name, starred = false))
    }

    override suspend fun setStarred(
        contactId: Long,
        starred: Boolean,
    ): AppResult<Unit> {
        contacts.value = contacts.value.map { if (it.id == contactId) it.copy(starred = starred) else it }
        return AppResult.Success(Unit)
    }
}

private class Placer : CallPlacer {
    var permitted = true
    val placed = mutableListOf<String>()

    override fun canPlaceCalls() = permitted

    override fun isEmergencyNumber(number: String) = false

    override fun place(
        number: String,
        simId: String?,
    ): AppResult<Unit> = AppResult.Success(Unit).also { placed += number }

    override fun placeEmergency(number: String): AppResult<Unit> = place(number)

    override fun placeVoicemail(simId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override fun sendSecretCode(digits: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun handOff(number: String): AppResult<Unit> = AppResult.Success(Unit)
}
