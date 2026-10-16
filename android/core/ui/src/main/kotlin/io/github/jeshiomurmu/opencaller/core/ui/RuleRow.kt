package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType

/**
 * One blocking rule (docs/design.md §5): pattern · type · action, an on/off switch and its actions (Test, Delete).
 * Block actions are labelled in words and use the danger colour only together with the word.
 */
@Composable
fun RuleRow(
    rule: BlockRule,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = stringResource(rule.type.labelRes())
    val action = stringResource(rule.action.labelRes())
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = rule.pattern + if (rule.type == RuleType.PREFIX) "*" else "", style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOf(type, action).joinToString(stringResource(R.string.risk_banner_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (rule.action ==
                        RuleAction.BLOCK
                    ) {
                        OpenCallerTheme.semanticColors.danger
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                TextButton(onClick = onTest) { Text(text = stringResource(R.string.rule_test)) }
                TextButton(onClick = onDelete) { Text(text = stringResource(R.string.rule_delete)) }
            }
        }
        val switchLabel = stringResource(R.string.rule_enabled, rule.pattern)
        Switch(
            checked = rule.enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.semantics { contentDescription = switchLabel },
        )
    }
}

/** Name of a rule type. */
@StringRes
fun RuleType.labelRes(): Int =
    when (this) {
        RuleType.EXACT -> R.string.rule_type_exact
        RuleType.PREFIX -> R.string.rule_type_prefix
        RuleType.COUNTRY -> R.string.rule_type_country
        RuleType.REGEX -> R.string.rule_type_regex
    }

/** Name of a rule action. */
@StringRes
fun RuleAction.labelRes(): Int =
    when (this) {
        RuleAction.BLOCK -> R.string.rule_action_block
        RuleAction.SILENCE -> R.string.rule_action_silence
        RuleAction.ALLOW -> R.string.rule_action_allow
    }

@OpenCallerPreviews
@Composable
private fun RuleRowPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                RuleRow(BlockRule(1, RuleType.PREFIX, "+1555123", RuleAction.BLOCK, RuleTarget.CALLS, 0, true), {}, {}, {})
                RuleRow(BlockRule(2, RuleType.EXACT, "+12015550123", RuleAction.ALLOW, RuleTarget.CALLS, 0, false), {}, {}, {})
            }
        }
    }
}
