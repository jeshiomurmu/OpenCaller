package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict

/** One sample caller per [CallerCardState], with STIR/SHAKEN lines on some. Fictional numbers only. */
internal class CallerCardSamples : PreviewParameterProvider<CallerInfo> {
    private val number = PhoneNumber("+12015550123")

    override val values: Sequence<CallerInfo> =
        sequenceOf(
            CallerInfo(number, "Amina Rahman", NameSource.CONTACT, lineType = LineType.MOBILE, regionCode = "US"),
            CallerInfo(number, "Jordan Lee", NameSource.SELF_REGISTERED, regionCode = "US", verification = CallerVerification.PASSED),
            CallerInfo(
                number,
                "City Bank",
                NameSource.VERIFIED_BUSINESS,
                verifiedBusinessId = "b1",
                callReason = "Card delivery",
                regionCode = "US",
            ),
            CallerInfo(
                number,
                spamVerdict = SpamVerdict(0.92f, RiskLevel.LIKELY_SPAM, "Loan", 214),
                regionCode = "US",
                verification = CallerVerification.FAILED,
            ),
            CallerInfo(number, spamVerdict = SpamVerdict(0.7f, RiskLevel.SUSPICIOUS, null, 12), lineType = LineType.FIXED_LINE_OR_MOBILE),
            CallerInfo(number, regionCode = "US", lineType = LineType.FIXED_LINE_OR_MOBILE),
            CallerInfo(number = null),
        )
}

@OpenCallerPreviews
@Composable
private fun CallerCardPreview(
    @PreviewParameter(CallerCardSamples::class) caller: CallerInfo,
) {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                CallerCardSurface {
                    CallerCard(caller = caller, formattedNumber = caller.number?.let { "(201) 555-0123" }) {
                        TextButton(onClick = {}) { Text(text = "Block") }
                    }
                }
            }
        }
    }
}
