package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRoute
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.SimChoice
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import io.github.jeshiomurmu.opencaller.feature.incall.CallView
import io.github.jeshiomurmu.opencaller.feature.incall.InCallUiState
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/** Who is on a call, in one line: a name with its source, else the number, else "Hidden number". */
@Composable
internal fun titleOf(view: CallView): String {
    val caller = view.caller
    return caller?.displayName?.takeIf { caller.nameSource != null } ?: view.number ?: stringResource(UiR.string.caller_card_hidden)
}

/** Sample calls for the previews. */
internal object InCallSamples {
    /** "Now" for the call timer (a call connected 1:15 ago). */
    const val NOW = 1_760_000_000_000L

    private val alex = PhoneNumber("+12015550123")
    private val loanScam = PhoneNumber("+12025550143")

    val contact =
        CallView(
            call =
                TrackedCall(
                    id = "1",
                    handle = alex.e164,
                    hidden = false,
                    status = CallStatus.ACTIVE,
                    direction = Direction.OUTGOING,
                    capabilities = setOf(CallCapability.HOLD, CallCapability.MUTE),
                    connectedAtMillis = NOW - 75_000,
                ),
            caller = CallerInfo(alex, displayName = "Alex Rahman", nameSource = NameSource.CONTACT),
            number = "(201) 555-0123",
        )

    val scamCaller =
        CallView(
            call =
                TrackedCall(
                    id = "2",
                    handle = loanScam.e164,
                    hidden = false,
                    status = CallStatus.RINGING,
                    direction = Direction.INCOMING,
                    capabilities = setOf(CallCapability.RESPOND_VIA_TEXT),
                ),
            caller =
                CallerInfo(
                    loanScam,
                    spamVerdict =
                        SpamVerdict(
                            score = 0.92f,
                            level = RiskLevel.LIKELY_SPAM,
                            category = "loan",
                            reportCount = 214,
                            source = VerdictSource.SPAM_LIST,
                        ),
                    regionCode = "US",
                ),
            number = "(202) 555-0143",
        )

    val choosingSim =
        CallView(
            call =
                TrackedCall(
                    id = "4",
                    handle = alex.e164,
                    hidden = false,
                    status = CallStatus.SELECT_SIM,
                    direction = Direction.OUTGOING,
                    simChoices = listOf(SimChoice("0", "Work"), SimChoice("1", "Personal")),
                ),
            caller = null,
            number = "(201) 555-0123",
        )

    val headsetAudio =
        CallAudio(
            current = AudioRoute("bt", AudioRouteType.BLUETOOTH, "Car kit"),
            available =
                listOf(
                    AudioRoute("ear", AudioRouteType.EARPIECE),
                    AudioRoute("spk", AudioRouteType.SPEAKER),
                    AudioRoute("bt", AudioRouteType.BLUETOOTH, "Car kit"),
                ),
        )

    val twoCalls =
        InCallUiState(
            loaded = true,
            primary = contact,
            held = scamCaller.copy(call = scamCaller.call.copy(id = "3", status = CallStatus.HOLDING)),
            audio = headsetAudio,
            canAddCall = true,
            swap = ConferenceAction.Resume("3"),
        )

    val ringingAlone = InCallUiState(loaded = true, ringing = scamCaller)
}
