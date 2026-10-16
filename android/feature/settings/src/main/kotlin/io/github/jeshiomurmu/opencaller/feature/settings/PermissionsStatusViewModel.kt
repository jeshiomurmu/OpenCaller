package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.AccessRequestOutcome
import io.github.jeshiomurmu.opencaller.core.model.AfterCallSurface
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequestFactory
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One role or permission on the status screen. [needsSettings]: the system won't show its dialog again. */
data class AccessRow(
    val item: AccessItem,
    val status: AccessItemStatus,
    val needsSettings: Boolean = false,
)

/**
 * Settings → Permissions & roles.
 *
 * @property explaining the item whose explainer is open (Fix was pressed), shown before any request.
 * @property pendingRequest a request the screen must start now, then report [PermissionsStatusEvent.RequestLaunched].
 */
data class PermissionsStatusUiState(
    val rows: List<AccessRow> = emptyList(),
    val callerIdSurface: CallerIdSurface = CallerIdSurface.NOTIFICATION,
    val afterCallSurface: AfterCallSurface = AfterCallSurface.NOTIFICATION,
    val explaining: AccessItem? = null,
    val explainerOpensSettings: Boolean = false,
    val pendingRequest: AccessRequest? = null,
    val settingsPageMissing: Boolean = false,
)

/** User actions on the status screen. */
sealed interface PermissionsStatusEvent {
    /** "Fix" on a row: open its explainer. */
    data class Fix(
        val item: AccessItem,
    ) : PermissionsStatusEvent

    /** Continue on the explainer. */
    data object ExplainerContinue : PermissionsStatusEvent

    /** "Not now" (or back) on the explainer. */
    data object ExplainerNotNow : PermissionsStatusEvent

    /** The screen started [PermissionsStatusUiState.pendingRequest]. */
    data object RequestLaunched : PermissionsStatusEvent

    /** The system dialog or settings page returned. */
    data class RequestResult(
        val outcome: AccessRequestOutcome,
    ) : PermissionsStatusEvent
}

/**
 * Shows every role and permission with a Fix action. Fix opens the item's explainer; Continue asks the
 * system, or opens the right settings page once the system stops showing its dialog.
 */
@HiltViewModel
class PermissionsStatusViewModel
    @Inject
    constructor(
        settings: UserSettingsDataSource,
        private val access: AccessStatusSource,
        private val requests: AccessRequestFactory,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val pending = MutableStateFlow<AccessRequest?>(null)
        private val settingsPageMissing = MutableStateFlow(false)
        private val explaining = savedState.getStateFlow<String?>(KEY_EXPLAINING, null)
        private val denials = savedState.getStateFlow(KEY_DENIALS, HashMap<String, Int>())

        /** Screen state. */
        val uiState: StateFlow<PermissionsStatusUiState> =
            combine(settings.preferences, access.status, explaining, denials, pending) { preferences, status, open, denied, request ->
                val openItem = open?.let(AccessItem::valueOf)
                PermissionsStatusUiState(
                    rows =
                        AccessItem.entries
                            .map { AccessRow(it, status.statusOf(it), needsSettings = it.isBlocked(denied)) }
                            .filter { it.status != AccessItemStatus.NOT_APPLICABLE },
                    callerIdSurface = status.callerIdSurface(preferences.overlayEnabled),
                    afterCallSurface = status.afterCallSurface,
                    explaining = openItem,
                    explainerOpensSettings = openItem?.let { opensSettings(it, denied) } == true,
                    pendingRequest = request,
                )
            }.combine(settingsPageMissing) { state, missing -> state.copy(settingsPageMissing = missing) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PermissionsStatusUiState())

        /** Opens [item]'s explainer once per screen (used when the degraded-mode banner's Fix opens this screen). */
        fun openOnce(item: AccessItem?) {
            if (item == null || savedState.get<Boolean>(KEY_OPENED_ONCE) == true) return
            savedState[KEY_OPENED_ONCE] = true
            onEvent(PermissionsStatusEvent.Fix(item))
        }

        /** Handles [event]. */
        fun onEvent(event: PermissionsStatusEvent) {
            when (event) {
                is PermissionsStatusEvent.Fix -> {
                    if (!access.status.value.isSettled(event.item)) {
                        savedState[KEY_EXPLAINING] =
                            event.item.name
                    }
                }

                PermissionsStatusEvent.ExplainerContinue -> {
                    askForOpenItem()
                }

                PermissionsStatusEvent.ExplainerNotNow -> {
                    savedState[KEY_EXPLAINING] = null
                }

                PermissionsStatusEvent.RequestLaunched -> {
                    pending.value = null
                }

                is PermissionsStatusEvent.RequestResult -> {
                    onResult(event.outcome)
                }
            }
        }

        // The only place a request is created: the user pressed Continue on an explainer.
        private fun askForOpenItem() {
            val item = explaining.value?.let(AccessItem::valueOf) ?: return
            settingsPageMissing.value = false
            val request = requestFor(item, denials.value)
            if (request == null) savedState[KEY_EXPLAINING] = null else pending.value = request
        }

        // Roles use a system dialog; everything else that isn't a runtime-permission dialog is a settings page.
        private fun opensSettings(
            item: AccessItem,
            denied: Map<String, Int>,
        ): Boolean = item.isBlocked(denied) || (!item.isRole() && requestFor(item, denied) is AccessRequest.SystemScreen)

        // Once blocked, even a role goes to its settings page, so the explainer says "Open settings".
        private fun requestFor(
            item: AccessItem,
            denied: Map<String, Int>,
        ): AccessRequest? =
            if (item.isBlocked(denied)) {
                requests.settingsFor(item)
            } else {
                requests.requestFor(item, access.status.value.roles)
            }

        private fun onResult(outcome: AccessRequestOutcome) {
            val item = explaining.value?.let(AccessItem::valueOf)
            access.refresh()
            if (item != null && !access.status.value.isSettled(item)) recordDenial(item, outcome)
            settingsPageMissing.value = outcome == AccessRequestOutcome.UNAVAILABLE
            savedState[KEY_EXPLAINING] = null
        }

        private fun recordDenial(
            item: AccessItem,
            outcome: AccessRequestOutcome,
        ) {
            val count = if (outcome == AccessRequestOutcome.BLOCKED) BLOCKED_AFTER else (denials.value[item.name] ?: 0) + 1
            savedState[KEY_DENIALS] = HashMap(denials.value + (item.name to count))
        }

        // Runtime permissions report BLOCKED directly. Role dialogs can't tell "no" from "don't ask again", so after
        // two refusals Fix goes to the default-apps settings page instead.
        private fun AccessItem.isBlocked(denied: Map<String, Int>): Boolean = (denied[name] ?: 0) >= BLOCKED_AFTER

        private fun AccessItem.isRole(): Boolean =
            this == AccessItem.CALL_SCREENING || this == AccessItem.DEFAULT_PHONE_APP || this == AccessItem.DEFAULT_SMS_APP

        private companion object {
            const val KEY_EXPLAINING = "permissions.explaining"
            const val KEY_DENIALS = "permissions.denials"
            const val KEY_OPENED_ONCE = "permissions.openedOnce"
            const val BLOCKED_AFTER = 2
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
