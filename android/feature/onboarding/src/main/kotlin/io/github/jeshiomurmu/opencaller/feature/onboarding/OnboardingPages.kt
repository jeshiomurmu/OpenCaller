package io.github.jeshiomurmu.opencaller.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.ui.iconRes
import io.github.jeshiomurmu.opencaller.core.ui.labelRes

@Composable
internal fun WelcomePage(
    progress: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        progress = progress,
        buttonLabel = stringResource(R.string.onboarding_get_started),
        onButton = onNext,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(OpenCallerIcons.Protection),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(OpenCallerSpacing.iconLarge),
        )
        Title(R.string.onboarding_welcome_title)
        Text(text = stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PrivacyPage(
    progress: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(progress = progress, buttonLabel = stringResource(R.string.onboarding_continue), onButton = onNext, modifier = modifier) {
        Icon(
            painter = painterResource(OpenCallerIcons.Privacy),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(OpenCallerSpacing.iconLarge),
        )
        Title(R.string.onboarding_privacy_title)
        PrivacyCard(
            heading = R.string.onboarding_privacy_leaves_title,
            lines =
                listOf(
                    R.string.onboarding_privacy_leaves_1,
                    R.string.onboarding_privacy_leaves_2,
                    R.string.onboarding_privacy_leaves_3,
                ),
        )
        PrivacyCard(
            heading = R.string.onboarding_privacy_never_title,
            lines =
                listOf(
                    R.string.onboarding_privacy_never_1,
                    R.string.onboarding_privacy_never_2,
                    R.string.onboarding_privacy_never_3,
                    R.string.onboarding_privacy_never_4,
                ),
        )
    }
}

@Composable
internal fun ExtrasPage(
    options: List<ExtraOption>,
    progress: String,
    onOpen: (AccessItem) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(progress = progress, buttonLabel = stringResource(R.string.onboarding_finish), onButton = onFinish, modifier = modifier) {
        Title(R.string.onboarding_extras_title)
        Text(text = stringResource(R.string.onboarding_extras_body), style = MaterialTheme.typography.bodyLarge)
        options.forEach { ExtraRow(option = it, onOpen = { onOpen(it.item) }) }
    }
}

@Composable
private fun ExtraRow(
    option: ExtraOption,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.padding(OpenCallerSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            Icon(painter = painterResource(option.item.iconRes()), contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(option.item.labelRes()), style = MaterialTheme.typography.titleMedium)
                statusLine(option.status)?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (option.status) {
                AccessItemStatus.NOT_GRANTED -> {
                    OutlinedButton(onClick = onOpen) { Text(text = stringResource(R.string.onboarding_set_up)) }
                }

                AccessItemStatus.GRANTED -> {
                    Icon(
                        painter = painterResource(OpenCallerIcons.Granted),
                        contentDescription = stringResource(R.string.onboarding_status_on),
                        tint = OpenCallerTheme.semanticColors.safe,
                    )
                }

                AccessItemStatus.COMING_LATER, AccessItemStatus.NOT_APPLICABLE -> {
                    // No action: the status line says why.
                }
            }
        }
    }
}

@StringRes
private fun statusLine(status: AccessItemStatus): Int? =
    when (status) {
        AccessItemStatus.COMING_LATER -> R.string.onboarding_status_coming_later
        AccessItemStatus.GRANTED -> R.string.onboarding_status_on
        AccessItemStatus.NOT_GRANTED -> R.string.onboarding_status_optional
        AccessItemStatus.NOT_APPLICABLE -> null
    }

@Composable
private fun PrivacyCard(
    @StringRes heading: Int,
    lines: List<Int>,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Text(
                text = stringResource(heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            lines.forEach {
                Text(
                    text = stringResource(R.string.onboarding_bullet, stringResource(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Title(
    @StringRes text: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** Scrollable content with the step line on top and one full-width button at the bottom. */
@Composable
private fun PageScaffold(
    progress: String,
    buttonLabel: String,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        ) {
            Text(text = progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        Button(onClick = onButton, shape = OpenCallerShape.pill, modifier = Modifier.fillMaxWidth()) {
            Text(text = buttonLabel)
        }
    }
}
