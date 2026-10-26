package io.github.jeshiomurmu.opencaller.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One of the contact's numbers.
 *
 * @property display the number for display (national format at home).
 * @property info what the device knows about the number besides the contact: the spam list, verified businesses,
 *   line type (`null` until looked up).
 */
data class ContactNumber(
    val phone: ContactPhone,
    val display: String,
    val info: CallerInfo?,
)

/**
 * The contact detail screen.
 *
 * @property gone the contact was deleted (the screen closes).
 * @property explainingPermission the contacts write permission's explainer is showing (for the favourite star).
 */
data class ContactDetailUiState(
    val loading: Boolean = true,
    val contact: ContactDetail? = null,
    val numbers: List<ContactNumber> = emptyList(),
    val gone: Boolean = false,
    val explainingPermission: Boolean = false,
    val failed: Boolean = false,
)

/** What the user did on the contact detail screen. */
sealed interface ContactDetailEvent {
    /** The screen opened for [contactId]; applied once. */
    data class Open(
        val contactId: Long,
    ) : ContactDetailEvent

    /** Add to or remove from the favourites. */
    data object ToggleFavourite : ContactDetailEvent

    /** Call [dialString]. */
    data class Call(
        val dialString: String,
    ) : ContactDetailEvent

    /** The permission dialog answered. */
    data class PermissionResult(
        val granted: Boolean,
    ) : ContactDetailEvent

    /** The explainer's Not now. */
    data object CancelPermission : ContactDetailEvent

    /** The failure message was read. */
    data object DismissFailure : ContactDetailEvent
}

/** Something the screen does once, outside the view model. */
sealed interface ContactDetailEffect {
    /** Open the dial pad with [number] (it asks for the call permission). */
    data class OpenDialPad(
        val number: String,
    ) : ContactDetailEffect
}

/** A contact's details, what the device knows about its numbers, and its actions (docs/prd.md F-CM-04). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactDetailViewModel
    @Inject
    constructor(
        private val contacts: ContactsSource,
        private val insights: NumberInsights,
        private val placer: CallPlacer,
        private val numbers: NumberDisplay,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val contactId = MutableStateFlow<Long?>(null)
        private val local = MutableStateFlow(ContactDetailUiState())
        private val effectChannel = Channel<ContactDetailEffect>(Channel.BUFFERED)

        /** One-off actions for the screen. */
        val effects: Flow<ContactDetailEffect> = effectChannel.receiveAsFlow()

        /** Screen state. */
        val uiState: StateFlow<ContactDetailUiState> =
            combine(contactId.filterNotNull().flatMapLatest(::details), local) { loaded, flags ->
                loaded.copy(explainingPermission = flags.explainingPermission, failed = flags.failed || loaded.failed)
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ContactDetailUiState())

        /** Handles [event]. */
        fun onEvent(event: ContactDetailEvent) {
            when (event) {
                is ContactDetailEvent.Open -> if (contactId.value == null) contactId.value = event.contactId
                ContactDetailEvent.ToggleFavourite -> toggleFavourite()
                is ContactDetailEvent.Call -> call(event.dialString)
                is ContactDetailEvent.PermissionResult -> permissionAnswered(event.granted)
                ContactDetailEvent.CancelPermission -> local.update { it.copy(explainingPermission = false) }
                ContactDetailEvent.DismissFailure -> local.update { it.copy(failed = false) }
            }
        }

        private fun details(id: Long): Flow<ContactDetailUiState> {
            if (!contacts.canRead()) return emptyFlow()
            val home = numbers.homeRegion()
            return contacts.observe(id).map { result ->
                when (result) {
                    is AppResult.Failure -> {
                        ContactDetailUiState(loading = false, failed = true)
                    }

                    is AppResult.Success -> {
                        val contact = result.value ?: return@map ContactDetailUiState(loading = false, gone = true)
                        val numbers =
                            contact.phones.map { phone ->
                                ContactNumber(
                                    phone,
                                    phone.number?.let { numbers.format(it, home) } ?: phone.dialString,
                                    insights.about(phone.dialString),
                                )
                            }
                        ContactDetailUiState(loading = false, contact = contact, numbers = numbers)
                    }
                }
            }
        }

        private fun toggleFavourite() {
            val contact = uiState.value.contact ?: return
            if (!contacts.canWrite()) {
                local.update { it.copy(explainingPermission = true) }
                return
            }
            viewModelScope.launch {
                if (contacts.setStarred(contact.id, !contact.starred) is AppResult.Failure) local.update { it.copy(failed = true) }
            }
        }

        private fun permissionAnswered(granted: Boolean) {
            local.update { it.copy(explainingPermission = false) }
            if (granted) toggleFavourite()
        }

        private fun call(dialString: String) {
            if (!placer.canPlaceCalls()) {
                effectChannel.trySend(ContactDetailEffect.OpenDialPad(dialString))
            } else if (placer.place(dialString) is AppResult.Failure) {
                local.update { it.copy(failed = true) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
