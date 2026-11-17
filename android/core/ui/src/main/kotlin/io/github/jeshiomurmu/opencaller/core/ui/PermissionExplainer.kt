package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * Shown before every permission or role request (docs/design.md §5, §7): an illustration, why OpenCaller
 * needs it, what it never does with it, and Continue / Not now. "Not now" is always offered.
 *
 * @param progress optional "Step 3 of 5" line above the title.
 * @param continueLabel defaults to "Continue"; use e.g. "Open settings" when Continue leaves the app.
 */
@Composable
fun PermissionExplainer(
    @DrawableRes icon: Int,
    title: String,
    why: String,
    never: String,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    progress: String? = null,
    continueLabel: String = stringResource(R.string.explainer_continue),
) {
    Column(
        modifier = modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        ) {
            progress?.let {
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(OpenCallerSpacing.iconLarge),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = why, style = MaterialTheme.typography.bodyLarge)
            NeverDoesCard(text = never)
        }
        Button(onClick = onContinue, shape = OpenCallerShape.pill, modifier = Modifier.fillMaxWidth()) {
            Text(text = continueLabel)
        }
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.explainer_not_now))
        }
    }
}

@Composable
private fun NeverDoesCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = OpenCallerShape.card,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
            Icon(
                painter = painterResource(OpenCallerIcons.Privacy),
                contentDescription = null,
                tint = OpenCallerTheme.semanticColors.safe,
                modifier = Modifier.align(Alignment.Start),
            )
            Text(text = stringResource(R.string.explainer_never_title), style = MaterialTheme.typography.titleMedium)
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OpenCallerPreviews
@Composable
private fun PermissionExplainerPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PermissionExplainer(
                icon = OpenCallerIcons.Contacts,
                title = "Show your own names for your contacts",
                why = "OpenCaller checks incoming numbers against your contacts on this phone.",
                never = "Your contacts never leave this phone. They are never uploaded or shared.",
                onContinue = {},
                onNotNow = {},
                progress = "Step 4 of 5",
            )
        }
    }
}
