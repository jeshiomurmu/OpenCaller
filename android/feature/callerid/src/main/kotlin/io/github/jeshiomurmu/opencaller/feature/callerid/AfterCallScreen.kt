package io.github.jeshiomurmu.opencaller.feature.callerid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.ui.CallerCard

/** The after-call screen, wired to its view model. */
@Composable
fun AfterCallRoute(
    viewModel: AfterCallViewModel,
    links: AfterCallLinks,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AfterCallScreen(state = state, onEvent = viewModel::onEvent, links = links, modifier = modifier)
}

/**
 * "Call ended" or "Missed call" with the caller card, then Call back, Message, Save contact, Block, and Report (or
 * Not spam for a flagged caller), and Close. Calm by default: no colours beyond the card's own (docs/design.md §1).
 */
@Composable
fun AfterCallScreen(
    state: AfterCallUiState,
    onEvent: (AfterCallEvent) -> Unit,
    links: AfterCallLinks,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(OpenCallerSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            Text(
                text = stringResource(if (state.answered) R.string.after_call_screen_ended else R.string.after_call_screen_missed),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            state.card?.let { CallerCard(caller = it.caller, formattedNumber = it.formattedNumber) }
            doneText(state.done, state.card?.maskedNumber)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier.semantics {
                            liveRegion =
                                LiveRegionMode.Polite
                        },
                )
            }
            AfterCallButtons(state = state, onEvent = onEvent, links = links)
        }
    }
}

@Composable
private fun AfterCallButtons(
    state: AfterCallUiState,
    onEvent: (AfterCallEvent) -> Unit,
    links: AfterCallLinks,
) {
    val wide = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget)
    val decided = state.done == AfterCallDone.BLOCKED || state.done == AfterCallDone.MARKED_NOT_SPAM
    Button(onClick = links.onCallBack, modifier = wide) { Text(stringResource(R.string.after_call_call_back)) }
    OutlinedButton(onClick = links.onMessage, modifier = wide) { Text(stringResource(R.string.after_call_message)) }
    OutlinedButton(onClick = links.onSaveContact, modifier = wide) { Text(stringResource(R.string.after_call_save_contact)) }
    OutlinedButton(onClick = { onEvent(AfterCallEvent.Block) }, enabled = !state.working && !decided, modifier = wide) {
        Text(stringResource(R.string.after_call_block))
    }
    if (state.flagged) {
        OutlinedButton(onClick = { onEvent(AfterCallEvent.NotSpam) }, enabled = !state.working && !decided, modifier = wide) {
            Text(stringResource(R.string.after_call_not_spam))
        }
    } else {
        OutlinedButton(onClick = links.onReport, modifier = wide) { Text(stringResource(R.string.after_call_report)) }
    }
    TextButton(onClick = links.onClose, modifier = Modifier.heightIn(min = OpenCallerSpacing.minTouchTarget)) {
        Text(stringResource(R.string.after_call_close))
    }
}

@Composable
private fun doneText(
    done: AfterCallDone?,
    masked: String?,
): String? =
    when (done) {
        AfterCallDone.BLOCKED -> stringResource(R.string.after_call_blocked, masked.orEmpty())
        AfterCallDone.MARKED_NOT_SPAM -> stringResource(R.string.after_call_marked_not_spam, masked.orEmpty())
        AfterCallDone.FAILED -> stringResource(R.string.after_call_failed)
        null -> null
    }

@OpenCallerPreviews
@Composable
private fun AfterCallScreenPreview() {
    val number = PhoneNumber("+12025550143")
    val caller =
        CallerInfo(number, spamVerdict = SpamVerdict(score = 0.7f, level = RiskLevel.SUSPICIOUS, category = "loan", reportCount = 12))
    OpenCallerTheme {
        AfterCallScreen(
            state = AfterCallUiState(card = CallerCardModel(caller, "(202) 555-0143", "+1 ••• 0143"), answered = false),
            onEvent = {},
            links = AfterCallLinks(onCallBack = {}, onMessage = {}, onSaveContact = {}, onReport = {}, onClose = {}),
        )
    }
}
