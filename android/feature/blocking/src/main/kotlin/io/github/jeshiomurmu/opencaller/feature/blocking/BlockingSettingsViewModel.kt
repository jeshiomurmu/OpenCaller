package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncState
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListUpdates
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.ui.ReportDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A report the user is about to fill in (from the after-call notification's Report or the blocked-calls log). */
data class PendingReport(
    val number: PhoneNumber,
    val display: String,
    val label: ReportLabel = ReportLabel.SPAM,
)

/**
 * The spam list on this phone.
 *
 * @property serverConfigured false: this build has no server, so the list never updates.
 * @property version list version on the phone, or `null` before the first update.
 * @property checkedAt when the server was last asked (epoch millis), or `null`.
 */
data class SpamListStatus(
    val serverConfigured: Boolean = false,
    val country: String? = null,
    val version: Long? = null,
    val checkedAt: Long? = null,
)

/** Short messages shown once, as a snackbar. */
sealed interface BlockingMessage {
    /** The report was queued (and the number blocked when [blocked]). */
    data class Reported(
        val masked: String,
        val blocked: Boolean,
    ) : BlockingMessage

    /** "Update now" was started; it runs as soon as the phone is online. */
    data object ListUpdateStarted : BlockingMessage

    /** Saving failed. */
    data object Failed : BlockingMessage
}

/**
 * Protection tab.
 *
 * @property contactsMissing "Block numbers not in contacts" is on but contacts can't be read, so it does nothing.
 * @property homeRegion the user's country, from the SIM.
 */
data class BlockingUiState(
    val loading: Boolean = true,
    val preferences: UserPreferences = UserPreferences(),
    val contactsMissing: Boolean = false,
    val homeRegion: String? = null,
    val countryError: Boolean = false,
    val report: PendingReport? = null,
    val message: BlockingMessage? = null,
    val signedIn: Boolean = false,
    val spamList: SpamListStatus = SpamListStatus(),
)

/** User actions on the Protection tab. */
sealed interface BlockingEvent {
    /** A toggle or the threshold changed. */
    data class Update(
        val change: (UserPreferences) -> UserPreferences,
    ) : BlockingEvent

    /** Allow calls from country [code] even when blocking foreign numbers. */
    data class AddCountry(
        val code: String,
    ) : BlockingEvent

    /** Stop allowing country [code]. */
    data class RemoveCountry(
        val code: String,
    ) : BlockingEvent

    /** The screen came back to the front: re-check the contacts permission. */
    data object Resumed : BlockingEvent

    /** Send the pending report as filled in on the report sheet. */
    data class SubmitReport(
        val draft: ReportDraft,
    ) : BlockingEvent

    /** Update the spam list now. */
    data object UpdateListNow : BlockingEvent

    /** Close the report sheet. */
    data object DismissReport : BlockingEvent

    /** The snackbar was shown. */
    data object MessageShown : BlockingEvent
}

/**
 * Blocking settings (toggles, threshold, allowed countries), the spam list's status and update settings, and reports
 * from the after-call notification.
 */
@HiltViewModel
class BlockingSettingsViewModel
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
        private val contacts: ContactLookup,
        private val homeRegion: HomeRegionProvider,
        private val normalizer: PhoneNumberNormalizer,
        private val reportNumber: ReportNumber,
        private val lists: SpamListUpdates,
    ) : ViewModel() {
        private val canReadContacts = MutableStateFlow(contacts.canRead())
        private val local = MutableStateFlow(BlockingUiState())

        /** Screen state. */
        val uiState: StateFlow<BlockingUiState> =
            combine(
                settings.preferences,
                canReadContacts,
                local,
                reportNumber.signedIn,
                lists.state,
            ) { prefs, canRead, state, signedIn, list ->
                state.copy(
                    loading = false,
                    preferences = prefs,
                    contactsMissing = prefs.blockNonContacts && !canRead,
                    homeRegion = homeRegion.homeRegion(),
                    signedIn = signedIn,
                    spamList = list.toStatus(lists.serverConfigured),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlockingUiState())

        /** Opens the report dialog for [e164] (once per screen). */
        fun showReport(e164: String) {
            if (!PhoneNumber.isE164(e164)) return
            val number = PhoneNumber(e164)
            local.value = local.value.copy(report = PendingReport(number, normalizer.format(number, homeRegion.homeRegion())))
        }

        /** Handles [event]. */
        fun onEvent(event: BlockingEvent) {
            when (event) {
                is BlockingEvent.Update -> viewModelScope.launch { settings.update(event.change) }
                is BlockingEvent.AddCountry -> addCountry(event.code)
                is BlockingEvent.RemoveCountry -> updateCountries { it - event.code }
                BlockingEvent.Resumed -> canReadContacts.value = contacts.canRead()
                is BlockingEvent.SubmitReport -> submitReport(event.draft)
                BlockingEvent.UpdateListNow -> updateListNow()
                BlockingEvent.DismissReport -> local.value = local.value.copy(report = null)
                BlockingEvent.MessageShown -> local.value = local.value.copy(message = null)
            }
        }

        private fun addCountry(input: String) {
            val code = input.trim().uppercase()
            val valid = code.length == 2 && normalizer.isSupportedRegion(code)
            local.value = local.value.copy(countryError = !valid)
            if (valid) updateCountries { it + code }
        }

        private fun updateCountries(change: (Set<String>) -> Set<String>) {
            viewModelScope.launch { settings.update { it.copy(allowedRegionCodes = change(it.allowedRegionCodes)) } }
        }

        private fun submitReport(draft: ReportDraft) {
            val report = local.value.report ?: return
            local.value = local.value.copy(report = null)
            viewModelScope.launch {
                val message =
                    when (val saved = reportNumber(report.number, draft)) {
                        is AppResult.Success -> BlockingMessage.Reported(normalizer.masked(report.number), saved.value)
                        is AppResult.Failure -> BlockingMessage.Failed
                    }
                local.value = local.value.copy(message = message)
            }
        }

        private fun updateListNow() {
            lists.updateNow()
            local.value = local.value.copy(message = BlockingMessage.ListUpdateStarted)
        }

        private fun SpamListSyncState.toStatus(serverConfigured: Boolean) = SpamListStatus(serverConfigured, country, version, checkedAt)

        /** Threshold choices in the order they are shown: block the most first. */
        companion object {
            /** Thresholds as shown. */
            val THRESHOLDS: List<SpamThreshold> = listOf(SpamThreshold.LOW, SpamThreshold.MEDIUM, SpamThreshold.HIGH)

            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
