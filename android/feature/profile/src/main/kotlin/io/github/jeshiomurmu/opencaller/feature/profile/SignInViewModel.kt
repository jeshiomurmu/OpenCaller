package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.Account
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the user is in sign-in. */
enum class SignInStep {
    /** Typing their number. */
    NUMBER,

    /** Typing the code from the SMS. */
    CODE,

    /** Signed in. */
    DONE,
}

/** Why the last step didn't work, in terms the screen can explain. */
sealed interface SignInProblem {
    /** The number isn't a valid phone number. */
    data object InvalidNumber : SignInProblem

    /** The code is wrong. */
    data object WrongCode : SignInProblem

    /** The code must be 6 digits. */
    data object CodeFormat : SignInProblem

    /** The code expired or was used up: request a new one. */
    data object CodeExpired : SignInProblem

    /** Too many codes or attempts; [retryAfterSeconds] when the server said. */
    data class TooMany(
        val retryAfterSeconds: Long?,
    ) : SignInProblem

    /** No connection. */
    data object Offline : SignInProblem

    /** This build has no server. */
    data object NotConnected : SignInProblem

    /** The server failed or couldn't send the SMS. */
    data object ServerError : SignInProblem
}

/**
 * Sign-in screen state.
 *
 * @property display the number formatted for display once it was accepted.
 * @property codeValidSeconds how long the sent code is valid.
 */
data class SignInUiState(
    val step: SignInStep = SignInStep.NUMBER,
    val number: PhoneNumber? = null,
    val display: String? = null,
    val codeValidSeconds: Long? = null,
    val busy: Boolean = false,
    val problem: SignInProblem? = null,
    val serverConfigured: Boolean = true,
)

/** User actions on the sign-in screen. */
sealed interface SignInEvent {
    /** Send a code to [input] (national or international format). */
    data class SendCode(
        val input: String,
    ) : SignInEvent

    /** Check [code]. */
    data class Verify(
        val code: String,
    ) : SignInEvent

    /** Send a new code to the same number. */
    data object ResendCode : SignInEvent

    /** Go back to the number. */
    data object ChangeNumber : SignInEvent
}

/** Optional sign-in (docs/architecture.md §4.7): number → code by SMS → done. */
@HiltViewModel
class SignInViewModel
    @Inject
    constructor(
        private val account: Account,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) : ViewModel() {
        private val state = MutableStateFlow(SignInUiState(serverConfigured = account.serverConfigured))

        /** Screen state. */
        val uiState: StateFlow<SignInUiState> =
            state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), state.value)

        /** Handles [event]. */
        fun onEvent(event: SignInEvent) {
            if (state.value.busy) return
            when (event) {
                is SignInEvent.SendCode -> sendCode(event.input)
                is SignInEvent.Verify -> verify(event.code)
                SignInEvent.ResendCode -> state.value.number?.let(::requestCode)
                SignInEvent.ChangeNumber -> state.value = SignInUiState(serverConfigured = account.serverConfigured)
            }
        }

        private fun sendCode(input: String) {
            val region = homeRegion.homeRegion() ?: DEFAULT_REGION
            when (val parsed = normalizer.normalize(input, region)) {
                is AppResult.Success -> requestCode(parsed.value)
                is AppResult.Failure -> state.value = state.value.copy(problem = SignInProblem.InvalidNumber)
            }
        }

        private fun requestCode(number: PhoneNumber) {
            state.value = state.value.copy(busy = true, problem = null)
            viewModelScope.launch {
                state.value =
                    when (val sent = account.sendCode(number)) {
                        is AppResult.Success -> {
                            val display = normalizer.format(number, homeRegion.homeRegion())
                            SignInUiState(SignInStep.CODE, number, display, sent.value, serverConfigured = account.serverConfigured)
                        }

                        is AppResult.Failure -> {
                            state.value.copy(busy = false, problem = sent.error.toProblem(sending = true))
                        }
                    }
            }
        }

        private fun verify(input: String) {
            val number = state.value.number ?: return
            val code = input.filter(Char::isDigit)
            if (code.length != CODE_LENGTH) {
                state.value = state.value.copy(problem = SignInProblem.CodeFormat)
                return
            }
            state.value = state.value.copy(busy = true, problem = null)
            viewModelScope.launch {
                state.value =
                    when (val verified = account.verify(number, code)) {
                        is AppResult.Success -> state.value.copy(step = SignInStep.DONE, busy = false)
                        is AppResult.Failure -> state.value.copy(busy = false, problem = verified.error.toProblem(sending = false))
                    }
            }
        }

        /** Sign-in rules. */
        companion object {
            /** Digits in a sign-in code. */
            const val CODE_LENGTH = 6
            private const val STOP_TIMEOUT_MILLIS = 5_000L

            // Without a SIM country, numbers must be typed with their country code; any region parses those.
            private const val DEFAULT_REGION = "ZZ"
        }
    }

/** Maps an [AppError] from sending or checking a code. */
internal fun AppError.toProblem(sending: Boolean): SignInProblem =
    when (this) {
        is AppError.Validation -> validationProblem(message, sending)
        is AppError.RateLimited -> SignInProblem.TooMany(retryAfterSeconds)
        AppError.Network -> SignInProblem.Offline
        AppError.ServerNotConfigured -> SignInProblem.NotConnected
        else -> SignInProblem.ServerError
    }

private fun validationProblem(
    code: String,
    sending: Boolean,
): SignInProblem =
    when {
        code == "invalid_code" -> SignInProblem.WrongCode
        code == "invalid_code_format" -> SignInProblem.CodeFormat
        code == "code_expired" || code == "code_invalidated" -> SignInProblem.CodeExpired
        code == "otp_delivery_failed" -> SignInProblem.ServerError
        sending -> SignInProblem.InvalidNumber
        else -> SignInProblem.ServerError
    }
