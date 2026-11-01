package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * A one-time code (docs/design.md): the code in Noto Sans Mono, who sent it, when it expires, Copy, and the reminder
 * never to share it. [expires] is already formatted (e.g. "expires 10:42"); `null` when the message gave no expiry.
 */
@Composable
fun OtpCard(
    code: String,
    from: String?,
    expires: String?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = OpenCallerShape.card,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Text(
                text = from?.let { stringResource(R.string.otp_card_from, it) } ?: stringResource(R.string.otp_card_title),
                style = MaterialTheme.typography.labelLarge,
            )
            // TalkBack reads the code digit by digit.
            val spoken = code.toList().joinToString(" ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m)) {
                Text(
                    text = code,
                    style = OpenCallerTheme.extendedTypography.otpCode,
                    modifier = Modifier.weight(1f).semantics { contentDescription = spoken },
                )
                OutlinedButton(onClick = onCopy, modifier = Modifier.heightIn(min = OpenCallerSpacing.minTouchTarget)) {
                    Icon(painterResource(OpenCallerIcons.Copy), contentDescription = null)
                    Text(text = stringResource(R.string.otp_card_copy), modifier = Modifier.padding(start = OpenCallerSpacing.xs))
                }
            }
            expires?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            Text(text = stringResource(R.string.otp_card_never_share), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OpenCallerPreviews
@Composable
private fun OtpCardPreview() {
    OpenCallerTheme { OtpCard(code = "482913", from = "Ridely", expires = "expires 10:42", onCopy = {}) }
}

@OpenCallerPreviews
@Composable
private fun OtpCardNoSenderPreview() {
    OpenCallerTheme { OtpCard(code = "7319", from = null, expires = null, onCopy = {}) }
}
