package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.ui.SourceChip
import io.github.jeshiomurmu.opencaller.core.ui.VerifiedBadge

/** The thread's name, its source chip (and the verified badge) and, under a name that isn't a contact's, the address. */
@Composable
internal fun ThreadTitle(
    state: ThreadUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        state.address?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.nameSource?.let { source ->
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                SourceChip(source = source)
                if (source == NameSource.VERIFIED_BUSINESS) VerifiedBadge()
            }
        }
    }
}
