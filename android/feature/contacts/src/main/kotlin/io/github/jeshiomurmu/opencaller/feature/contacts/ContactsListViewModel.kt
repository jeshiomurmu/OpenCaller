package io.github.jeshiomurmu.opencaller.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.Normalizer
import javax.inject.Inject

/** Whether the contacts can be shown. */
enum class ContactsAccess {
    /** Not known yet. */
    CHECKING,

    /** The contacts permission is needed. */
    NEEDS_PERMISSION,

    /** The contacts are shown. */
    READY,
}

/** The contacts whose names start with [letter] ("#" for digits and symbols). */
data class ContactSection(
    val letter: String,
    val contacts: List<ContactSummary>,
)

/**
 * The contacts list.
 *
 * @property favourites the starred contacts, shown first (none while searching).
 * @property sections everyone, by first letter, for the fast scroller.
 */
data class ContactsListUiState(
    val access: ContactsAccess = ContactsAccess.CHECKING,
    val query: String = "",
    val favourites: List<ContactSummary> = emptyList(),
    val sections: List<ContactSection> = emptyList(),
    val failed: Boolean = false,
)

/** What the user did on the contacts list. */
sealed interface ContactsListEvent {
    /** Search for [query]. */
    data class Search(
        val query: String,
    ) : ContactsListEvent

    /** The permission dialog answered or the screen came back: read again. */
    data object Reload : ContactsListEvent
}

/** The contacts list (docs/prd.md F-CM-04): Android's contacts, read on the device, never synced. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactsListViewModel
    @Inject
    constructor(
        private val contacts: ContactsSource,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val reload = MutableStateFlow(0)

        /** Screen state. */
        val uiState: StateFlow<ContactsListUiState> =
            combine(reload.flatMapLatest { all() }, query, ::build)
                .flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ContactsListUiState())

        /** Handles [event]. */
        fun onEvent(event: ContactsListEvent) {
            when (event) {
                is ContactsListEvent.Search -> query.value = event.query
                ContactsListEvent.Reload -> reload.update { it + 1 }
            }
        }

        private fun all(): Flow<AppResult<List<ContactSummary>>?> = if (contacts.canRead()) contacts.observeAll() else flowOf(null)

        private fun build(
            result: AppResult<List<ContactSummary>>?,
            query: String,
        ): ContactsListUiState =
            when {
                result == null || (result is AppResult.Failure && result.error == AppError.PermissionDenied) -> {
                    ContactsListUiState(ContactsAccess.NEEDS_PERMISSION, query)
                }

                result is AppResult.Failure -> {
                    ContactsListUiState(ContactsAccess.READY, query, failed = true)
                }

                else -> {
                    val shown = ContactIndex.search((result as AppResult.Success).value, query)
                    ContactsListUiState(
                        access = ContactsAccess.READY,
                        query = query,
                        favourites = if (query.isBlank()) shown.filter { it.starred } else emptyList(),
                        sections = ContactIndex.sections(shown),
                    )
                }
            }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** Grouping and search of the contacts list. Pure. */
object ContactIndex {
    /** The letter a [name] is listed under: its first letter without accents, upper-case, or "#". */
    fun letterOf(name: String): String {
        val first = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).firstOrNull { !Character.getType(it).isMark() } ?: return OTHER
        return if (first.isLetter()) first.uppercaseChar().toString() else OTHER
    }

    /** [contacts] (sorted by name) in sections by letter, "#" last. */
    fun sections(contacts: List<ContactSummary>): List<ContactSection> =
        contacts
            .groupBy { letterOf(it.name) }
            .map { (letter, group) -> ContactSection(letter, group) }
            .sortedWith(compareBy<ContactSection> { it.letter == OTHER }.thenBy { it.letter })

    /** The contacts whose name contains [query] (ignoring case) or whose numbers contain its digits. */
    fun search(
        contacts: List<ContactSummary>,
        query: String,
    ): List<ContactSummary> {
        val text = query.trim()
        if (text.isEmpty()) return contacts
        val digits = text.filter { it in '0'..'9' }
        return contacts.filter { contact ->
            contact.name.contains(text, ignoreCase = true) ||
                (
                    digits.isNotEmpty() &&
                        contact.phones.any { phone -> (phone.number?.e164 ?: phone.dialString).filter { it in '0'..'9' }.contains(digits) }
                )
        }
    }

    private const val OTHER = "#"

    private fun Int.isMark(): Boolean =
        this == Character.NON_SPACING_MARK.toInt() || this == Character.COMBINING_SPACING_MARK.toInt() ||
            this == Character.ENCLOSING_MARK.toInt()
}
