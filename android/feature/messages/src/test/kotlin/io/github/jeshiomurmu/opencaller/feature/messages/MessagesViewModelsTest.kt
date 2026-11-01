package io.github.jeshiomurmu.opencaller.feature.messages

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Attachment
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageBox
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MessagingSim
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ParticipantNames
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ConversationsEvent
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ConversationsViewModel
import io.github.jeshiomurmu.opencaller.feature.messages.ui.MessageStatus
import io.github.jeshiomurmu.opencaller.feature.messages.ui.NewMessageEffect
import io.github.jeshiomurmu.opencaller.feature.messages.ui.NewMessageEvent
import io.github.jeshiomurmu.opencaller.feature.messages.ui.NewMessageViewModel
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadEvent
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ana = person("+12015550111")
    private val ram = person("+12015550122")
    private val conversations = FakeConversations()
    private val sending = FakeSending()
    private val presence = FakePresence()
    private val numbers = NumberDisplay(PhoneNumberNormalizer()) { "US" }
    private val contactNames = mapOf(ana.number to "Ana Silva")
    private val names =
        ParticipantNames(
            object : ContactLookup {
                override fun canRead() = true

                override suspend fun displayName(number: PhoneNumber): String? = contactNames[number]
            },
            numbers,
        )

    private fun TestScope.dispatchers() = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun conversationsAreNamedFromContactsAndNewestFirst() =
        runTest {
            val forgotten = mutableListOf<Long>()
            val vm = ConversationsViewModel(conversations, names, { forgotten += it }, dispatchers())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            conversations.list.value =
                listOf(
                    Conversation(1, listOf(ana), "See you", 2_000, unreadCount = 2),
                    Conversation(2, listOf(ana, ram), "", 1_000, lastFromMe = true, lastHasImage = true),
                )

            val rows = vm.uiState.value.rows
            assertEquals(listOf("Ana Silva", "Ana Silva, (201) 555-0122"), rows.map { it.title })
            assertEquals(2, rows[0].unread)
            assertTrue(rows[1].isGroup && rows[1].fromMe && rows[1].hasImage)

            vm.onEvent(ConversationsEvent.AskDelete(rows[1]))
            assertEquals(rows[1], vm.uiState.value.confirmDelete)
            vm.onEvent(ConversationsEvent.ConfirmDelete)
            assertEquals(listOf(2L), conversations.deleted)
            assertEquals("its meta rows go too", listOf(2L), forgotten)
            assertNull(vm.uiState.value.confirmDelete)
        }

    @Test
    fun withoutTheRoleNothingIsRead() =
        runTest {
            conversations.readable = false
            val vm = ConversationsViewModel(conversations, names, {}, dispatchers())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertFalse(vm.uiState.value.canRead)
            assertTrue(
                vm.uiState.value.rows
                    .isEmpty(),
            )
        }

    @Test
    fun aFailedDeleteIsShown() =
        runTest {
            conversations.failDelete = true
            conversations.list.value = listOf(Conversation(1, listOf(ana), "x", 1))
            val vm = ConversationsViewModel(conversations, names, {}, dispatchers())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.onEvent(
                ConversationsEvent.AskDelete(
                    vm.uiState.value.rows
                        .single(),
                ),
            )
            vm.onEvent(ConversationsEvent.ConfirmDelete)
            assertTrue(vm.uiState.value.failed)
        }

    @Test
    fun tcSms001TheThreadShowsMessagesWithTheirStates() =
        runTest {
            conversations.people[7] = listOf(ana)
            conversations.threads.value = mapOf(7L to sixMessages())
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7))

            val state = vm.uiState.value
            assertEquals("Ana Silva", state.title)
            assertEquals(
                listOf(
                    MessageStatus.NONE,
                    MessageStatus.DELIVERED,
                    MessageStatus.SENDING,
                    MessageStatus.FAILED,
                    MessageStatus.NONE,
                    MessageStatus.NONE,
                ),
                state.messages.map { it.status },
            )
            assertEquals(listOf("content://mms/part/9"), state.messages[4].images)
            assertEquals(MmsDownload.AVAILABLE, state.messages[5].download)

            vm.onEvent(ThreadEvent.Retry(state.messages[3].key))
            vm.onEvent(ThreadEvent.Download(state.messages[5].key))
            assertEquals(listOf(MessageKey(MessageKind.SMS, 4)), sending.retried)
            assertEquals(listOf(6L), sending.downloaded)
        }

    @Test
    fun tcSms002SendingGoesToEveryoneAndClearsTheComposer() =
        runTest {
            conversations.people[7] = listOf(ana, ram)
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7, draft = "From a link"))
            assertEquals("From a link", vm.uiState.value.draft)
            assertTrue(vm.uiState.value.isGroup)

            vm.onEvent(ThreadEvent.Draft("  Dinner?  "))
            vm.onEvent(ThreadEvent.AddPictures(listOf("content://picker/1", "content://picker/1")))
            vm.onEvent(ThreadEvent.Send)

            assertEquals(listOf(listOf(listOf(ana.address, ram.address), "Dinner?", listOf("content://picker/1"), null)), sending.sent)
            assertEquals("", vm.uiState.value.draft)
            assertTrue(
                vm.uiState.value.pictures
                    .isEmpty(),
            )
        }

    @Test
    fun aFailedSendKeepsTheDraft() =
        runTest {
            conversations.people[7] = listOf(ana)
            sending.result = AppResult.Failure(AppError.PermissionDenied)
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7))
            vm.onEvent(ThreadEvent.Draft("Hello"))
            vm.onEvent(ThreadEvent.Send)
            assertEquals("Hello", vm.uiState.value.draft)
            assertTrue(vm.uiState.value.failed)
        }

    @Test
    fun withTwoSimsAndNoDefaultTheFirstSendAsks() =
        runTest {
            conversations.people[7] = listOf(ana)
            sending.sims = MessagingSims(listOf(MessagingSim(1, "Work", 0), MessagingSim(2, "", 1)), defaultId = null)
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7))
            vm.onEvent(ThreadEvent.Draft("Hello"))
            vm.onEvent(ThreadEvent.Send)
            assertTrue(vm.uiState.value.choosingSim)
            assertTrue(sending.sent.isEmpty())

            vm.onEvent(ThreadEvent.PickSim(2))
            assertEquals(2, sending.sent.single()[3])
            assertFalse(vm.uiState.value.choosingSim)
        }

    @Test
    fun aNewConversationSendsToTheRecipientsItWasOpenedWith() =
        runTest {
            // Android's store knows a thread's people only from its messages; a new thread has none yet.
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(8, draft = "Hi", recipients = listOf("+12015550111")))
            assertEquals("Ana Silva", vm.uiState.value.title)
            vm.onEvent(ThreadEvent.Send)
            assertEquals(listOf("+12015550111"), sending.sent.single()[0])
        }

    @Test
    fun theDefaultSimIsPreselected() =
        runTest {
            conversations.people[7] = listOf(ana)
            sending.sims = MessagingSims(listOf(MessagingSim(1, "Work", 0), MessagingSim(2, "Home", 1)), defaultId = 2)
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7))
            assertEquals(2, vm.uiState.value.simId)
        }

    @Test
    fun anOpenThreadIsReadAndHasNoNotification() =
        runTest {
            val vm = thread()
            vm.onEvent(ThreadEvent.Open(7))
            vm.onEvent(ThreadEvent.Visible(true))
            assertEquals(listOf(7L), presence.shown)
            vm.onEvent(ThreadEvent.Visible(false))
            assertEquals(1, presence.hidden)
        }

    @Test
    fun aThreadInFrontBeforeItOpensIsReadToo() =
        runTest {
            // The screen's resume callback runs before its Open event.
            val vm = thread()
            vm.onEvent(ThreadEvent.Visible(true))
            assertEquals(emptyList<Long>(), presence.shown)
            vm.onEvent(ThreadEvent.Open(7))
            assertEquals(listOf(7L), presence.shown)
        }

    @Test
    fun aNewMessagePicksContactsAndTypedNumbers() =
        runTest {
            val vm = NewMessageViewModel(contactsOf(PhoneNumber("+12015550111")), conversations, numbers, dispatchers())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.onEvent(NewMessageEvent.Query("ana"))
            val suggestion =
                vm.uiState.value.suggestions
                    .single()
            assertEquals("Ana Silva · (201) 555-0111", suggestion.label)
            vm.onEvent(NewMessageEvent.Add(suggestion))
            assertEquals("", vm.uiState.value.query)
            assertTrue(
                "already added",
                vm.uiState.value.suggestions
                    .isEmpty(),
            )

            vm.onEvent(NewMessageEvent.Query("+1 201 555 0133"))
            val effect =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    assertEquals(
                        NewMessageEffect.OpenThread(FakeConversations.NEW_THREAD, listOf("+12015550111", "+12015550133")),
                        vm.effects.first(),
                    )
                }
            vm.onEvent(NewMessageEvent.Next)
            assertEquals("a typed number counts on Next", setOf("+12015550111", "+12015550133"), conversations.threadForAsked)
            assertTrue(effect.isCompleted)
        }

    // Received, delivered, sending, failed, a received picture, and an MMS still on the carrier's server.
    private fun sixMessages(): List<Message> =
        listOf(
            message(1, MessageBox.INBOX, from = ana, body = "Hi"),
            message(2, MessageBox.SENT, delivery = DeliveryState.DELIVERED),
            message(3, MessageBox.OUTBOX),
            message(4, MessageBox.FAILED),
            Message(
                MessageKey(MessageKind.MMS, 5),
                7,
                MessageBox.INBOX,
                ana,
                "",
                5,
                attachments = listOf(Attachment("content://mms/part/9", "image/jpeg")),
            ),
            Message(MessageKey(MessageKind.MMS, 6), 7, MessageBox.INBOX, ana, "", 6, download = MmsDownload.AVAILABLE, sizeBytes = 2048),
        )

    private fun TestScope.thread(): ThreadViewModel =
        ThreadViewModel(conversations, names, sending, presence, dispatchers()).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    private fun message(
        id: Long,
        box: MessageBox,
        from: io.github.jeshiomurmu.opencaller.core.model.Participant? = null,
        body: String = "m$id",
        delivery: DeliveryState = DeliveryState.NONE,
    ) = Message(MessageKey(MessageKind.SMS, id), 7, box, from, body, id, delivery = delivery)

    private fun contactsOf(number: PhoneNumber): ContactsSource =
        object : ContactsSource {
            override fun canRead() = true

            override fun canWrite() = false

            override fun observeAll(): Flow<AppResult<List<ContactSummary>>> =
                flowOf(
                    AppResult.Success(
                        listOf(
                            ContactSummary(1, "k", "Ana Silva", false, listOf(ContactPhone(number, "(201) 555-0111", PhoneLabel.MOBILE))),
                        ),
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
}
