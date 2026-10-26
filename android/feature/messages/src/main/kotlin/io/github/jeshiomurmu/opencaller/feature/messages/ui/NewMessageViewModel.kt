package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessageAddresses
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Someone the message goes to: [address] is what is sent to, [label] what the chip shows. */
data class Recipient(
    val address: String,
    val label: String,
)

/** The new-message screen. */
data class NewMessageUiState(
    val query: String = "",
    val recipients: List<Recipient> = emptyList(),
    val suggestions: List<Recipient> = emptyList(),
    val opening: Boolean = false,
    val failed: Boolean = false,
) {
    /** True when what was typed can be added as a number. */
    val canAddTyped: Boolean get() = query.count(Char::isDigit) >= MIN_DIGITS

    private companion object {
        const val MIN_DIGITS = 3
    }
}

/** What the user did. */
sealed interface NewMessageEvent {
    /** The search field changed. */
    data class Query(
        val text: String,
    ) : NewMessageEvent

    /** Add [recipient] (a contact's number). */
    data class Add(
        val recipient: Recipient,
    ) : NewMessageEvent

    /** Add the typed number. */
    data object AddTyped : NewMessageEvent

    /** Remove the recipient with [address]. */
    data class Remove(
        val address: String,
    ) : NewMessageEvent

    /** Open the conversation with the recipients. */
    data object Next : NewMessageEvent

    /** The failure was read. */
    data object DismissFailure : NewMessageEvent
}

/** Something the screen does once. */
sealed interface NewMessageEffect {
    /** Open thread [threadId]. */
    data class OpenThread(
        val threadId: Long,
    ) : NewMessageEffect
}

/**
 * Starts a conversation: pick contacts' numbers or type numbers (several make a group message), then the
 * conversation with exactly those people opens (Android finds or creates its thread).
 */
@HiltViewModel
class NewMessageViewModel
    @Inject
    constructor(
        private val contacts: ContactsSource,
        private val conversations: ConversationSource,
        private val numbers: NumberDisplay,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val local = MutableStateFlow(NewMessageUiState())
        private val effectChannel = Channel<NewMessageEffect>(Channel.BUFFERED)

        /** One-off actions for the screen. */
        val effects: Flow<NewMessageEffect> = effectChannel.receiveAsFlow()

        /** Screen state. */
        val uiState: StateFlow<NewMessageUiState> =
            combine(phoneBook(), local) { book, state -> state.copy(suggestions = suggestions(book, state)) }
                .flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), NewMessageUiState())

        /** Handles [event]. */
        fun onEvent(event: NewMessageEvent) {
            when (event) {
                is NewMessageEvent.Query -> {
                    local.update { it.copy(query = event.text) }
                }

                is NewMessageEvent.Add -> {
                    add(event.recipient)
                }

                NewMessageEvent.AddTyped -> {
                    addTyped()
                }

                is NewMessageEvent.Remove -> {
                    local.update { state ->
                        state.copy(
                            recipients =
                                state.recipients.filterNot {
                                    it.address ==
                                        event.address
                                },
                        )
                    }
                }

                NewMessageEvent.Next -> {
                    next()
                }

                NewMessageEvent.DismissFailure -> {
                    local.update { it.copy(failed = false) }
                }
            }
        }

        private fun phoneBook(): Flow<List<Recipient>> =
            if (contacts.canRead()) {
                contacts.observeAll().map {
                    recipientsOf(it.getOrNull().orEmpty())
                }
            } else {
                flowOf(emptyList())
            }

        private fun recipientsOf(people: List<ContactSummary>): List<Recipient> =
            people.flatMap { contact ->
                contact.phones.map { phone ->
                    val shown = phone.number?.let { numbers.format(it) } ?: phone.dialString
                    Recipient(address = phone.number?.e164 ?: phone.dialString, label = "${contact.name} · $shown")
                }
            }

        private fun suggestions(
            book: List<Recipient>,
            state: NewMessageUiState,
        ): List<Recipient> {
            val query = state.query.trim()
            if (query.isEmpty()) return emptyList()
            val digits = query.filter(Char::isDigit)
            return book
                .filter {
                    it.label.contains(query, ignoreCase = true) ||
                        (digits.length >= MIN_MATCH_DIGITS && it.address.filter(Char::isDigit).contains(digits))
                }.filterNot { suggestion -> state.recipients.any { MessageAddresses.same(it.address, suggestion.address) } }
                .take(MAX_SUGGESTIONS)
        }

        private fun add(recipient: Recipient) {
            local.update { state ->
                if (state.recipients.any { MessageAddresses.same(it.address, recipient.address) }) {
                    state.copy(query = "")
                } else {
                    state.copy(recipients = state.recipients + recipient, query = "")
                }
            }
        }

        private fun addTyped() {
            val typed = local.value.query.trim()
            if (!local.value.canAddTyped) return
            add(Recipient(address = typed.filter { it.isDigit() || it == '+' }, label = typed))
        }

        // A number typed but not added yet counts too.
        private fun next() {
            if (local.value.canAddTyped) addTyped()
            val addresses =
                local.value.recipients
                    .map(Recipient::address)
                    .toSet()
            if (addresses.isEmpty() || local.value.opening) return
            local.update { it.copy(opening = true) }
            viewModelScope.launch {
                when (val thread = conversations.threadFor(addresses)) {
                    is AppResult.Success -> effectChannel.send(NewMessageEffect.OpenThread(thread.value))
                    is AppResult.Failure -> local.update { it.copy(failed = true) }
                }
                local.update { it.copy(opening = false) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MAX_SUGGESTIONS = 20
            const val MIN_MATCH_DIGITS = 3
        }
    }
