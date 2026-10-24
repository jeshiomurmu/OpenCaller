package io.github.jeshiomurmu.opencaller.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhoneDraft
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a contact wasn't saved. */
enum class ContactEditProblem {
    /** Neither a name nor a number. */
    EMPTY,

    /** Android refused the change (for example a read-only account). */
    FAILED,

    /** The contacts write permission was refused. */
    NO_PERMISSION,
}

/**
 * The create/edit screen.
 *
 * @property isNew true for a new contact, false when editing one.
 * @property savedContactId set once the contact is saved (the screen closes and opens it).
 */
data class ContactEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: ContactDraft = ContactDraft(),
    val saving: Boolean = false,
    val explainingPermission: Boolean = false,
    val problem: ContactEditProblem? = null,
    val savedContactId: Long? = null,
)

/** What the user did on the create/edit screen. */
sealed interface ContactEditEvent {
    /** The screen opened to edit [contactId], or to create a contact (with [number] filled in); applied once. */
    data class Prepare(
        val contactId: Long?,
        val number: String?,
    ) : ContactEditEvent

    /** The name changed. */
    data class Name(
        val name: String,
    ) : ContactEditEvent

    /** Number [index] changed. */
    data class Phone(
        val index: Int,
        val dialString: String,
    ) : ContactEditEvent

    /** Number [index]'s label changed. */
    data class Label(
        val index: Int,
        val label: PhoneLabel,
    ) : ContactEditEvent

    /** Add an empty number. */
    data object AddPhone : ContactEditEvent

    /** Remove number [index]. */
    data class RemovePhone(
        val index: Int,
    ) : ContactEditEvent

    /** E-mail [index] changed ([index] = the size adds one). */
    data class Email(
        val index: Int,
        val address: String,
    ) : ContactEditEvent

    /** Save. */
    data object Save : ContactEditEvent

    /** The permission dialog answered. */
    data class PermissionResult(
        val granted: Boolean,
    ) : ContactEditEvent

    /** The explainer's Not now. */
    data object CancelPermission : ContactEditEvent
}

/**
 * Create and edit contacts in Android's contacts provider, as one batch (docs/prd.md F-CM-04). The write permission
 * is asked for on Save, after an explainer.
 */
@HiltViewModel
class ContactEditViewModel
    @Inject
    constructor(
        private val contacts: ContactsSource,
    ) : ViewModel() {
        private val state = MutableStateFlow(ContactEditUiState())
        private var prepared = false
        private var rawContactId: Long? = null

        /** Screen state. */
        val uiState: StateFlow<ContactEditUiState> = state.asStateFlow()

        /** Handles [event]. */
        fun onEvent(event: ContactEditEvent) {
            when (event) {
                is ContactEditEvent.Prepare -> {
                    prepare(event.contactId, event.number)
                }

                is ContactEditEvent.Name -> {
                    edit { it.copy(name = event.name) }
                }

                is ContactEditEvent.Phone -> {
                    edit {
                        it.copy(phones = it.phones.replace(event.index) { phone -> phone.copy(dialString = event.dialString) })
                    }
                }

                is ContactEditEvent.Label -> {
                    edit {
                        it.copy(
                            phones = it.phones.replace(event.index) { phone -> phone.copy(label = event.label) },
                        )
                    }
                }

                ContactEditEvent.AddPhone -> {
                    edit { it.copy(phones = it.phones + ContactPhoneDraft("")) }
                }

                is ContactEditEvent.RemovePhone -> {
                    edit { it.copy(phones = it.phones.filterIndexed { i, _ -> i != event.index }) }
                }

                is ContactEditEvent.Email -> {
                    edit { it.copy(emails = it.emails.set(event.index, event.address)) }
                }

                else -> {
                    onSaveEvent(event)
                }
            }
        }

        private fun onSaveEvent(event: ContactEditEvent) {
            when (event) {
                ContactEditEvent.Save -> save()
                is ContactEditEvent.PermissionResult -> permissionAnswered(event.granted)
                else -> state.update { it.copy(explainingPermission = false) }
            }
        }

        private fun prepare(
            contactId: Long?,
            number: String?,
        ) {
            if (prepared) return
            prepared = true
            if (contactId == null) {
                state.update { it.copy(loading = false, draft = ContactDraft(phones = listOf(ContactPhoneDraft(number.orEmpty())))) }
                return
            }
            viewModelScope.launch {
                val detail = (contacts.observe(contactId).first() as? AppResult.Success)?.value
                rawContactId = detail?.rawContactId
                val draft =
                    detail?.let {
                        ContactDraft(
                            name = it.name,
                            phones =
                                it.phones
                                    .map { phone ->
                                        ContactPhoneDraft(phone.dialString, phone.label)
                                    }.ifEmpty { listOf(ContactPhoneDraft("")) },
                            emails = it.emails,
                        )
                    } ?: ContactDraft()
                state.update { it.copy(loading = false, isNew = detail == null, draft = draft) }
            }
        }

        private fun edit(change: (ContactDraft) -> ContactDraft) {
            state.update { it.copy(draft = change(it.draft), problem = null) }
        }

        private fun save() {
            val current = state.value
            if (current.saving) return
            when {
                !current.draft.canSave -> state.update { it.copy(problem = ContactEditProblem.EMPTY) }
                !contacts.canWrite() -> state.update { it.copy(explainingPermission = true) }
                else -> write(current.draft)
            }
        }

        private fun write(draft: ContactDraft) {
            state.update { it.copy(saving = true, problem = null) }
            viewModelScope.launch {
                when (val result = contacts.save(rawContactId, draft)) {
                    is AppResult.Success -> state.update { it.copy(saving = false, savedContactId = result.value) }
                    is AppResult.Failure -> state.update { it.copy(saving = false, problem = ContactEditProblem.FAILED) }
                }
            }
        }

        private fun permissionAnswered(granted: Boolean) {
            state.update { it.copy(explainingPermission = false) }
            if (granted) save() else state.update { it.copy(problem = ContactEditProblem.NO_PERMISSION) }
        }
    }

private fun <T> List<T>.replace(
    index: Int,
    change: (T) -> T,
): List<T> = mapIndexed { i, item -> if (i == index) change(item) else item }

// Sets e-mail [index]; one past the end adds it.
private fun List<String>.set(
    index: Int,
    value: String,
): List<String> = if (index >= size) this + value else replace(index) { value }
