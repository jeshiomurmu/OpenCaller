package io.github.jeshiomurmu.opencaller.feature.calllog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Whether the call log can be shown. */
enum class CallLogAccess {
    /** Not known yet. */
    CHECKING,

    /** The call log permission is needed (asked after an explainer, only as the default phone app). */
    NEEDS_PERMISSION,

    /** The log is shown. */
    READY,
}

/**
 * One row: the calls with one number on one day.
 *
 * @property display the number for display, `null` for a hidden caller.
 * @property callBack what Call dials, `null` for a hidden caller.
 * @property contactId the contact with this number, if any.
 */
data class CallLogRow(
    val key: String,
    val ids: List<Long>,
    val name: String?,
    val number: PhoneNumber?,
    val display: String?,
    val callBack: String?,
    val type: CallType,
    val count: Int,
    val atMillis: Long,
    val flags: CallFlags,
    val contactId: Long?,
)

/** The rows of one day, newest first. */
data class CallLogSection(
    val day: LocalDate,
    val rows: List<CallLogRow>,
)

/** A message about what just happened. */
sealed interface CallLogNotice {
    /** [masked] was blocked; Undo removes rule [ruleId]. */
    data class Blocked(
        val masked: String,
        val ruleId: Long,
    ) : CallLogNotice

    /** Something failed (the call log or the rules couldn't be changed). */
    data object Failed : CallLogNotice
}

/** The call log screen. [today] is the day the screen labels "Today". */
data class CallLogUiState(
    val access: CallLogAccess = CallLogAccess.CHECKING,
    val filter: CallFilter = CallFilter.ALL,
    val query: String = "",
    val today: LocalDate? = null,
    val sections: List<CallLogSection> = emptyList(),
    val notice: CallLogNotice? = null,
    val confirmDelete: CallLogRow? = null,
)

/** What the user did on the call log. */
sealed interface CallLogEvent {
    /** Show only [filter]'s calls. */
    data class SetFilter(
        val filter: CallFilter,
    ) : CallLogEvent

    /** Search for [query]. */
    data class Search(
        val query: String,
    ) : CallLogEvent

    /** Call [row]'s number back. */
    data class Call(
        val row: CallLogRow,
    ) : CallLogEvent

    /** Block [row]'s number. */
    data class Block(
        val row: CallLogRow,
    ) : CallLogEvent

    /** Undo the block just made. */
    data object UndoBlock : CallLogEvent

    /** Ask before deleting [row]'s calls. */
    data class AskDelete(
        val row: CallLogRow,
    ) : CallLogEvent

    /** Delete the row being asked about. */
    data object ConfirmDelete : CallLogEvent

    /** Keep it. */
    data object CancelDelete : CallLogEvent

    /** The permission dialog answered, or the screen came back: read the log again. */
    data object Reload : CallLogEvent

    /** The notice was read. */
    data object DismissNotice : CallLogEvent
}

/** Something the screen does once, outside the view model. */
sealed interface CallLogEffect {
    /** Open the dial pad with [number] (calling needs the call permission, which the dial pad asks for). */
    data class OpenDialPad(
        val number: String,
    ) : CallLogEffect
}

/** Who a number belongs to among the contacts. */
private data class KnownContact(
    val name: String,
    val id: Long,
)

/** The data the screen is built from. */
private data class Snapshot(
    val access: CallLogAccess,
    val calls: List<CallLogEntry> = emptyList(),
    val events: List<ScreeningEvent> = emptyList(),
    val contacts: Map<String, KnownContact> = emptyMap(),
)

/** What the user chose on the screen. */
private data class Controls(
    val filter: CallFilter = CallFilter.ALL,
    val query: String = "",
    val notice: CallLogNotice? = null,
    val confirmDelete: CallLogRow? = null,
)

/**
 * The call log (docs/prd.md F-CM-03): Android's call log joined on the device with OpenCaller's screening log and the
 * contacts, grouped by number and day, with the missed, blocked and spam filters (TC-LOG-001) and search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CallLogViewModel
    @Inject
    constructor(
        private val inputs: CallLogInputs,
        private val blocker: NumberBlocker,
        private val placer: CallPlacer,
        private val numbers: NumberDisplay,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val controls = MutableStateFlow(Controls())
        private val reload = MutableStateFlow(0)
        private val effectChannel = Channel<CallLogEffect>(Channel.BUFFERED)
        private val zone: ZoneId = ZoneId.systemDefault()
        private val merger = CallHistory(zone)

        /** One-off actions for the screen. */
        val effects: Flow<CallLogEffect> = effectChannel.receiveAsFlow()

        /** Screen state. */
        val uiState: StateFlow<CallLogUiState> =
            combine(reload.flatMapLatest { snapshots() }, controls, ::build)
                .flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CallLogUiState())

        /** Handles [event]. */
        fun onEvent(event: CallLogEvent) {
            when (event) {
                is CallLogEvent.SetFilter -> controls.update { it.copy(filter = event.filter) }
                is CallLogEvent.Search -> controls.update { it.copy(query = event.query) }
                is CallLogEvent.Call -> call(event.row)
                is CallLogEvent.Block -> block(event.row)
                CallLogEvent.UndoBlock -> undoBlock()
                is CallLogEvent.AskDelete -> controls.update { it.copy(confirmDelete = event.row) }
                CallLogEvent.ConfirmDelete -> delete()
                CallLogEvent.CancelDelete -> controls.update { it.copy(confirmDelete = null) }
                CallLogEvent.Reload -> reload.update { it + 1 }
                CallLogEvent.DismissNotice -> controls.update { it.copy(notice = null) }
            }
        }

        private fun snapshots(): Flow<Snapshot> {
            if (!inputs.calls.canRead()) return flowOf(Snapshot(CallLogAccess.NEEDS_PERMISSION))
            val contacts = inputs.contacts
            val names = if (contacts.canRead()) contacts.observeAll().map { phoneBook(it.getOrNull().orEmpty()) } else flowOf(emptyMap())
            return combine(inputs.calls.observe(), inputs.screening.recent(), names) { calls, events, known ->
                when (calls) {
                    is AppResult.Success -> Snapshot(CallLogAccess.READY, calls.value, events, known)
                    is AppResult.Failure -> Snapshot(CallLogAccess.NEEDS_PERMISSION)
                }
            }
        }

        private fun build(
            snapshot: Snapshot,
            controls: Controls,
        ): CallLogUiState {
            val home = numbers.homeRegion()
            val flags = merger.flags(snapshot.calls, snapshot.events)
            val nameOf = { call: CallLogEntry -> call.number?.let { snapshot.contacts[it.e164]?.name } }
            val shown = merger.search(merger.filter(snapshot.calls, flags, controls.filter), controls.query, nameOf)
            val sections =
                merger
                    .group(shown, flags)
                    .groupBy { it.day }
                    .map { (day, groups) -> CallLogSection(day, groups.map { rowOf(it, snapshot.contacts, home) }) }
            return CallLogUiState(
                access = snapshot.access,
                filter = controls.filter,
                query = controls.query,
                today = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate(),
                sections = sections,
                notice = controls.notice,
                confirmDelete = controls.confirmDelete,
            )
        }

        private fun rowOf(
            group: CallGroup,
            contacts: Map<String, KnownContact>,
            home: String,
        ): CallLogRow {
            val latest = group.latest
            val contact = latest.number?.let { contacts[it.e164] }
            return CallLogRow(
                key = "${group.key}/${group.day}",
                ids = group.calls.map { it.id },
                name = contact?.name ?: latest.cachedName,
                number = latest.number,
                display = latest.number?.let { numbers.format(it, home) } ?: latest.dialString.takeIf(String::isNotBlank),
                callBack = latest.callBackString,
                type = latest.type,
                count = group.count,
                atMillis = latest.atMillis,
                flags = group.flags,
                contactId = contact?.id,
            )
        }

        private fun call(row: CallLogRow) {
            val number = row.callBack ?: return
            if (!placer.canPlaceCalls()) {
                effectChannel.trySend(CallLogEffect.OpenDialPad(number))
                return
            }
            if (placer.place(number) is AppResult.Failure) controls.update { it.copy(notice = CallLogNotice.Failed) }
        }

        private fun block(row: CallLogRow) {
            val number = row.number ?: return
            viewModelScope.launch {
                val notice =
                    when (val result = blocker.block(number)) {
                        is AppResult.Success -> CallLogNotice.Blocked(numbers.masked(number), result.value)
                        is AppResult.Failure -> CallLogNotice.Failed
                    }
                controls.update { it.copy(notice = notice) }
            }
        }

        private fun undoBlock() {
            val blocked = controls.value.notice as? CallLogNotice.Blocked ?: return
            controls.update { it.copy(notice = null) }
            viewModelScope.launch {
                if (blocker.undo(blocked.ruleId) is AppResult.Failure) controls.update { it.copy(notice = CallLogNotice.Failed) }
            }
        }

        private fun delete() {
            val row = controls.value.confirmDelete ?: return
            controls.update { it.copy(confirmDelete = null) }
            viewModelScope.launch {
                if (inputs.calls.delete(row.ids) is AppResult.Failure) controls.update { it.copy(notice = CallLogNotice.Failed) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

// Each contact's numbers (E.164) with the contact's name and id.
private fun phoneBook(contacts: List<ContactSummary>): Map<String, KnownContact> =
    contacts
        .flatMap { contact ->
            contact.phones.mapNotNull { phone -> phone.number?.e164 }.map { it to KnownContact(contact.name, contact.id) }
        }.toMap()
