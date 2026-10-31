package io.github.jeshiomurmu.opencaller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state.
 *
 * @property degraded OpenCaller can't screen calls (call screening role missing): show the banner.
 * @property defaultPhoneApp status of the default phone app role when the Calls tab should offer it, else null.
 * @property defaultSmsApp status of the default SMS app role when the Messages tab should offer it, else null.
 */
data class AppUiState(
    val loading: Boolean = true,
    val showOnboarding: Boolean = false,
    val degraded: Boolean = false,
    val defaultPhoneApp: AccessItemStatus? = null,
    val isDefaultPhoneApp: Boolean = false,
    val defaultSmsApp: AccessItemStatus? = null,
    val isDefaultSmsApp: Boolean = false,
)

/** The app was opened to dial [number] (`ACTION_DIAL`; `null` opens an empty dial pad). */
data class DialRequest(
    val number: String?,
)

/** Open conversation [threadId] (a new message when `null`) with [draft] in the composer and its [recipients]. */
data class MessageRequest(
    val threadId: Long?,
    val draft: String? = null,
    val recipients: List<String> = emptyList(),
)

/** Decides between onboarding and the app shell, and what the shell warns about. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settings: UserSettingsDataSource,
        access: AccessStatusSource,
        private val conversations: ConversationSource,
    ) : ViewModel() {
        /** App state. */
        val uiState: StateFlow<AppUiState> =
            combine(settings.preferences, access.status) { preferences, status ->
                AppUiState(
                    loading = false,
                    showOnboarding = preferences.onboardingStep != OnboardingStep.DONE,
                    degraded = status.isDegraded,
                    defaultPhoneApp =
                        status
                            .statusOf(AccessItem.DEFAULT_PHONE_APP)
                            .takeIf { it == AccessItemStatus.NOT_GRANTED || it == AccessItemStatus.COMING_LATER },
                    isDefaultPhoneApp = status.statusOf(AccessItem.DEFAULT_PHONE_APP) == AccessItemStatus.GRANTED,
                    defaultSmsApp =
                        status.statusOf(AccessItem.DEFAULT_SMS_APP).takeIf {
                            it == AccessItemStatus.NOT_GRANTED ||
                                it == AccessItemStatus.COMING_LATER
                        },
                    isDefaultSmsApp = status.statusOf(AccessItem.DEFAULT_SMS_APP) == AccessItemStatus.GRANTED,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppUiState())

        private val report = MutableStateFlow<String?>(null)

        /** A number (E.164) the user asked to report from the after-call notification, until the dialog shows. */
        val pendingReport: StateFlow<String?> = report.asStateFlow()

        /** The app was opened by the after-call notification's Report for [e164]. */
        fun requestReport(e164: String?) {
            if (e164 != null && PhoneNumber.isE164(e164)) report.value = e164
        }

        /** The report dialog is showing. */
        fun reportShown() {
            report.value = null
        }

        private val dial = MutableStateFlow<DialRequest?>(null)

        /** A dial request (`ACTION_DIAL`) until the dial pad shows. */
        val pendingDial: StateFlow<DialRequest?> = dial.asStateFlow()

        /** The app was opened by `ACTION_DIAL`, with the number of a `tel:` link if any. */
        fun requestDial(number: String?) {
            dial.value = DialRequest(number?.takeIf(String::isNotBlank))
        }

        /** The dial pad is showing. */
        fun dialShown() {
            dial.value = null
        }

        private val message = MutableStateFlow<MessageRequest?>(null)

        /** A conversation to open (a send-to link, a message notification) until it shows. */
        val pendingMessage: StateFlow<MessageRequest?> = message.asStateFlow()

        /** A send-to link to [recipients] with [draft]: their conversation, or a new message without recipients. */
        fun requestMessage(
            recipients: List<String>,
            draft: String?,
        ) {
            if (recipients.isEmpty()) {
                message.value = MessageRequest(threadId = null, draft = draft)
                return
            }
            viewModelScope.launch {
                message.value = MessageRequest(conversations.threadFor(recipients.toSet()).getOrNull(), draft, recipients)
            }
        }

        /** A message notification for thread [threadId] was tapped. */
        fun openThread(threadId: Long) {
            message.value = MessageRequest(threadId)
        }

        /** The conversation is showing. */
        fun messageShown() {
            message.value = null
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
