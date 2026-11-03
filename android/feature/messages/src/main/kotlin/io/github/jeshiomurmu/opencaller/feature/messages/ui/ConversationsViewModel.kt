package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ParticipantNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One conversation in the list. [title] is the people's names (or numbers), comma-separated for a group; a
 * one-to-one conversation's name comes with its [nameSource] (docs/prd.md F-ID-09), `null` when [title] is the
 * number or address itself.
 */
data class ConversationRow(
    val threadId: Long,
    val title: String,
    val snippet: String,
    val atMillis: Long,
    val unread: Int,
    val isGroup: Boolean,
    val fromMe: Boolean,
    val hasImage: Boolean,
    val category: MessageCategory = MessageCategory.PERSONAL,
    val risky: Boolean = false,
    val nameSource: NameSource? = null,
)

/** The smart inbox: the [tab] shown with its conversations, every tab's unread count, the current code. */
data class ConversationsUiState(
    val loading: Boolean = true,
    val canRead: Boolean = true,
    val tab: InboxTab = InboxTab.PERSONAL,
    val tabs: List<TabSummary> = InboxTab.entries.map { TabSummary(it, 0) },
    val rows: List<ConversationRow> = emptyList(),
    val otp: OtpCardState? = null,
    val confirmDelete: ConversationRow? = null,
    val cleanPlan: CleanPlan? = null,
    val autoDeleteCodes: Boolean = false,
    val cleaned: Int? = null,
    val failed: Boolean = false,
)

/** What the user did on the list. */
sealed interface ConversationsEvent {
    /** The screen came back: read again (the role or permissions may have changed). */
    data object Reload : ConversationsEvent

    /** Show [tab]. */
    data class SelectTab(
        val tab: InboxTab,
    ) : ConversationsEvent

    /** Ask before deleting [row]. */
    data class AskDelete(
        val row: ConversationRow,
    ) : ConversationsEvent

    /** Delete the conversation asked about. */
    data object ConfirmDelete : ConversationsEvent

    /** Keep it. */
    data object CancelDelete : ConversationsEvent

    /** Open the inbox cleaner (it counts what it would delete). */
    data object AskClean : ConversationsEvent

    /** Delete what the cleaner found. */
    data object ConfirmClean : ConversationsEvent

    /** Close the cleaner. */
    data object CancelClean : ConversationsEvent

    /** Turn the deletion of codes after a day on or off. */
    data class AutoDeleteCodes(
        val enabled: Boolean,
    ) : ConversationsEvent

    /** The failure or the "cleaned" message was read. */
    data object DismissMessage : ConversationsEvent
}

/** OpenCaller's metadata of the received messages, and forgetting a deleted conversation's. */
interface ThreadMetadata {
    /** Every message's metadata, kept current. */
    fun observe(): Flow<List<MessageMeta>>

    /** Forgets thread [threadId]. */
    suspend fun forget(threadId: Long)
}

private data class Controls(
    val tab: InboxTab = InboxTab.PERSONAL,
    val confirmDelete: ConversationRow? = null,
    val cleanPlan: CleanPlan? = null,
    val cleaned: Int? = null,
    val failed: Boolean = false,
)

/**
 * The smart inbox (PRD F-SMS-03): the default SMS app's conversations, newest first, named from the user's contacts
 * and sorted into tabs by the category of their newest classified message; the spam folder is the Spam tab. The OTP
 * tab shows the current code on a card. The cleaner deletes old codes, deliveries, promotions and spam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationsViewModel
    @Inject
    constructor(
        private val conversations: ConversationSource,
        private val names: ParticipantNames,
        private val meta: ThreadMetadata,
        private val cleanup: InboxCleanup,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val reload = MutableStateFlow(0)
        private val controls = MutableStateFlow(Controls())
        private val loaded = reload.flatMapLatest { loaded() }

        /** Screen state. */
        val uiState: StateFlow<ConversationsUiState> =
            combine(loaded, controls, cleanup.autoDelete()) { all, local, autoDelete ->
                all.copy(
                    tab = local.tab,
                    rows = all.rows.filter { InboxTab.of(it.category) == local.tab },
                    otp = all.otp.takeIf { local.tab == InboxTab.OTP },
                    confirmDelete = local.confirmDelete,
                    cleanPlan = local.cleanPlan,
                    autoDeleteCodes = autoDelete,
                    cleaned = local.cleaned,
                    failed = all.failed || local.failed,
                )
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConversationsUiState())

        /** Handles [event]. */
        fun onEvent(event: ConversationsEvent) {
            when (event) {
                ConversationsEvent.Reload -> reload.update { it + 1 }
                is ConversationsEvent.SelectTab -> controls.update { it.copy(tab = event.tab) }
                is ConversationsEvent.AskDelete -> controls.update { it.copy(confirmDelete = event.row) }
                ConversationsEvent.ConfirmDelete -> delete()
                ConversationsEvent.CancelDelete -> controls.update { it.copy(confirmDelete = null) }
                ConversationsEvent.AskClean -> plan()
                ConversationsEvent.ConfirmClean -> clean()
                ConversationsEvent.CancelClean -> controls.update { it.copy(cleanPlan = null) }
                is ConversationsEvent.AutoDeleteCodes -> viewModelScope.launch { cleanup.setAutoDelete(event.enabled) }
                ConversationsEvent.DismissMessage -> controls.update { it.copy(failed = false, cleaned = null) }
            }
        }

        // All conversations with their facts; the tab filter is applied in uiState.
        private fun loaded(): Flow<ConversationsUiState> {
            if (!conversations.canRead()) return flowOf(ConversationsUiState(loading = false, canRead = false))
            return combine(conversations.observeConversations(), meta.observe()) { result, metas ->
                when (result) {
                    is AppResult.Success -> {
                        val facts = threadFacts(metas)
                        val rows = result.value.map { rowOf(it, facts[it.threadId] ?: ThreadFacts()) }
                        ConversationsUiState(
                            loading = false,
                            rows = rows,
                            tabs = tabSummaries(rows),
                            otp = currentOtp(metas, clock.nowMillis()),
                        )
                    }

                    is AppResult.Failure -> {
                        ConversationsUiState(loading = false, failed = true)
                    }
                }
            }
        }

        private suspend fun rowOf(
            conversation: Conversation,
            facts: ThreadFacts,
        ): ConversationRow {
            val (title, source) = titleOf(conversation, facts)
            return ConversationRow(
                threadId = conversation.threadId,
                title = title,
                snippet = conversation.snippet,
                atMillis = conversation.lastAtMillis,
                unread = conversation.unreadCount,
                isGroup = conversation.isGroup,
                fromMe = conversation.lastFromMe,
                hasImage = conversation.lastHasImage,
                category = facts.category,
                risky = facts.risky,
                nameSource = source,
            )
        }

        // One person: the contact, else the name the message pipeline found (registry, verified business, …), else
        // the address. A group lists contact names or addresses.
        private suspend fun titleOf(
            conversation: Conversation,
            facts: ThreadFacts,
        ): Pair<String, NameSource?> {
            val single = conversation.participants.singleOrNull()
            val contact = single?.let { names.contactName(it) }
            return when {
                single == null -> conversation.participants.map { names.title(it) }.joinToString(", ") to null
                contact != null -> contact to NameSource.CONTACT
                facts.senderName != null -> facts.senderName to facts.senderSource
                else -> names.address(single) to null
            }
        }

        private fun delete() {
            val row = controls.value.confirmDelete ?: return
            controls.update { it.copy(confirmDelete = null) }
            viewModelScope.launch {
                when (conversations.delete(row.threadId)) {
                    is AppResult.Success -> meta.forget(row.threadId)
                    is AppResult.Failure -> controls.update { it.copy(failed = true) }
                }
            }
        }

        private fun plan() {
            viewModelScope.launch {
                when (val plan = cleanup.plan()) {
                    is AppResult.Success -> controls.update { it.copy(cleanPlan = plan.value) }
                    is AppResult.Failure -> controls.update { it.copy(failed = true) }
                }
            }
        }

        private fun clean() {
            val plan = controls.value.cleanPlan ?: return
            controls.update { it.copy(cleanPlan = null) }
            viewModelScope.launch {
                when (val cleaned = cleanup.clean(plan)) {
                    is AppResult.Success -> controls.update { it.copy(cleaned = cleaned.value) }
                    is AppResult.Failure -> controls.update { it.copy(failed = true) }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
