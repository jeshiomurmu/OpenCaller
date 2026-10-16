package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerElevation
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import java.util.Locale

/**
 * Who is calling, decided in one glance (docs/design.md §1, §5): avatar, name in `headlineMedium` with its
 * [SourceChip] (a name is never shown without its source), the risk banner, a meta row (country, line type, reports),
 * the carrier's STIR/SHAKEN verdict and its actions. Risk is always icon + words, never colour alone.
 *
 * @param formattedNumber the caller's number formatted for display (national format when in the user's country).
 * @param content the actions row (e.g. Block, Close).
 */
@Composable
fun CallerCard(
    caller: CallerInfo,
    formattedNumber: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit = {},
) {
    val state = CallerCardState.of(caller)
    val name = caller.displayName?.takeIf { caller.nameSource != null }
    Column(
        modifier = modifier.fillMaxWidth().padding(OpenCallerSpacing.m),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            CallerAvatar(state = state, name = name)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
                Text(
                    text = name ?: formattedNumber ?: stringResource(state.fallbackTitle()),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (name != null && formattedNumber != null) {
                    Text(
                        text = formattedNumber,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                caller.nameSource?.takeIf { name != null }?.let { SourceChip(source = it) }
                if (caller.isVerifiedBusiness) VerifiedBadge()
            }
        }
        caller.spamVerdict?.let { RiskBanner(level = it.level, category = it.category, reportCount = it.reportCount) }
        CallerDetails(caller = caller, state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), content = content)
    }
}

@Composable
private fun CallerDetails(
    caller: CallerInfo,
    state: CallerCardState,
) {
    val meta = metaLine(caller)
    if (meta.isNotEmpty()) {
        Text(text = meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    caller.callReason?.let {
        Text(
            text = stringResource(R.string.caller_card_call_reason, it),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    VerificationLine(caller.verification)
    val note =
        when (state) {
            CallerCardState.HIDDEN -> R.string.caller_card_hidden_note
            CallerCardState.UNKNOWN -> R.string.caller_card_no_data
            else -> null
        }
    note?.let {
        Text(text = stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun metaLine(caller: CallerInfo): String {
    val parts =
        buildList {
            caller.regionCode?.let { region ->
                Locale
                    .Builder()
                    .setRegion(region)
                    .build()
                    .displayCountry
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
            caller.lineType?.labelRes()?.let { add(stringResource(it)) }
            val reports = caller.spamVerdict?.reportCount ?: 0
            if (reports > 0 && caller.spamVerdict?.level == RiskLevel.NONE) {
                add(pluralStringResource(R.plurals.risk_report_count, reports, reports))
            }
        }
    return parts.joinToString(stringResource(R.string.risk_banner_separator))
}

@Composable
private fun VerificationLine(verification: CallerVerification) {
    val semantic = OpenCallerTheme.semanticColors
    val (icon, text, color) =
        when (verification) {
            CallerVerification.PASSED -> Triple(OpenCallerIcons.Verified, R.string.caller_card_stir_passed, semantic.safe)
            CallerVerification.FAILED -> Triple(OpenCallerIcons.Warning, R.string.caller_card_stir_failed, semantic.warning)
            CallerVerification.NOT_VERIFIED, CallerVerification.UNKNOWN -> return
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(OpenCallerSpacing.iconSmall),
        )
        Text(text = stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun CallerAvatar(
    state: CallerCardState,
    name: String?,
) {
    val semantic = OpenCallerTheme.semanticColors
    val (background, content) =
        when (state) {
            CallerCardState.SPAM -> semantic.dangerContainer to semantic.onDangerContainer
            CallerCardState.SUSPICIOUS -> semantic.warningContainer to semantic.warning
            else -> MaterialTheme.colorScheme.surfaceContainerHigh to state.accent()
        }
    Box(modifier = Modifier.size(OpenCallerSpacing.avatar).background(background, CircleShape), contentAlignment = Alignment.Center) {
        val initial = name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (initial != null && state != CallerCardState.SPAM) {
            Text(text = initial.toString(), style = MaterialTheme.typography.titleMedium, color = content)
        } else {
            Icon(painter = painterResource(state.icon()), contentDescription = null, tint = content)
        }
    }
}

@Composable
private fun CallerCardState.accent(): Color =
    when (this) {
        CallerCardState.CONTACT -> OpenCallerTheme.semanticColors.safe
        CallerCardState.VERIFIED_BUSINESS -> OpenCallerTheme.semanticColors.business
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun CallerCardState.icon(): Int =
    when (this) {
        CallerCardState.CONTACT -> OpenCallerIcons.Contacts
        CallerCardState.SELF_REGISTERED -> OpenCallerIcons.SelfRegistered
        CallerCardState.VERIFIED_BUSINESS -> OpenCallerIcons.Verified
        CallerCardState.SPAM -> OpenCallerIcons.Danger
        CallerCardState.SUSPICIOUS -> OpenCallerIcons.Warning
        CallerCardState.UNKNOWN -> OpenCallerIcons.Me
        CallerCardState.HIDDEN -> OpenCallerIcons.Privacy
    }

@StringRes
private fun CallerCardState.fallbackTitle(): Int =
    if (this ==
        CallerCardState.HIDDEN
    ) {
        R.string.caller_card_hidden
    } else {
        R.string.caller_card_unknown
    }

/** Short label of a line type, or `null` when unknown. */
@StringRes
internal fun LineType.labelRes(): Int? =
    when (this) {
        LineType.FIXED_LINE -> R.string.line_fixed
        LineType.MOBILE -> R.string.line_mobile
        LineType.FIXED_LINE_OR_MOBILE -> R.string.line_fixed_or_mobile
        LineType.TOLL_FREE -> R.string.line_toll_free
        LineType.PREMIUM_RATE -> R.string.line_premium_rate
        LineType.SHARED_COST -> R.string.line_shared_cost
        LineType.VOIP -> R.string.line_voip
        LineType.PERSONAL_NUMBER -> R.string.line_personal
        LineType.PAGER -> R.string.line_pager
        LineType.UAN -> R.string.line_uan
        LineType.VOICEMAIL -> R.string.line_voicemail
        LineType.UNKNOWN -> null
    }

/** The caller card on its own surface, as drawn over other apps (rounded, elevated). */
@Composable
fun CallerCardSurface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = OpenCallerElevation.callerCardOverlay,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = OpenCallerShape.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = tonalElevation,
        shadowElevation = tonalElevation,
        content = content,
    )
}
