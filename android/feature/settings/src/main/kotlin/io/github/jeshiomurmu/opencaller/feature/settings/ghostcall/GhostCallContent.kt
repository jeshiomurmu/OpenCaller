package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.feature.settings.R
import kotlinx.coroutines.delay

/** The fake call: ringing with Answer and Decline, then "on a call" with a timer and Hang up. */
@Composable
fun GhostCallContent(
    setup: GhostCallSetup,
    photo: ImageBitmap?,
    answered: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(OpenCallerSpacing.xl),
        ) {
            Caller(setup, photo, answered)
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                CallButton(
                    stringResource(if (answered) R.string.ghost_call_hang_up else R.string.ghost_call_decline),
                    danger = true,
                    onClick = onDecline,
                )
                if (!answered) CallButton(stringResource(R.string.ghost_call_answer), danger = false, onClick = onAnswer)
            }
        }
    }
}

@Composable
private fun Caller(
    setup: GhostCallSetup,
    photo: ImageBitmap?,
    answered: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Text(
            text = if (answered) callTime() else stringResource(R.string.ghost_call_incoming),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(PHOTO_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            if (photo != null) {
                Image(bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(setup.callerName.take(1).uppercase(), style = MaterialTheme.typography.displayMedium)
            }
        }
        Text(
            text = setup.callerName.ifBlank { stringResource(R.string.ghost_call_default_name) },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (setup.displayNumber.isNotBlank()) Text(setup.displayNumber, style = MaterialTheme.typography.titleMedium)
    }
}

// Seconds since the fake call was answered, as m:ss.
@Composable
private fun callTime(): String {
    var seconds by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ONE_SECOND)
            seconds++
        }
    }
    return stringResource(R.string.ghost_call_time, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

@Composable
private fun CallButton(
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val colors =
        if (danger) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors(
                containerColor = OpenCallerTheme.semanticColors.safe,
                contentColor = MaterialTheme.colorScheme.surface,
            )
        }
    Button(onClick = onClick, colors = colors, modifier = Modifier.size(BUTTON_SIZE)) { Text(label, textAlign = TextAlign.Center) }
}

private val PHOTO_SIZE = 120.dp
private val BUTTON_SIZE = 96.dp
private const val ONE_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60

@OpenCallerPreviews
@Composable
private fun GhostCallRingingPreview() {
    OpenCallerTheme {
        GhostCallContent(GhostCallSetup("Mum", "+44 7700 900123"), photo = null, answered = false, onAnswer = {}, onDecline = {})
    }
}
