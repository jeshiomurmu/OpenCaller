package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** The twelve keys of a phone keypad, row by row. */
private val KEY_ROWS = listOf("123", "456", "789", "*0#")

/**
 * A phone keypad (dial pad and in-call keypad). [onKeyDown] fires when a key is pressed and [onKeyUp] when it is
 * released, so a call's keypad tone lasts as long as the press. TalkBack activates a key with one double tap
 * (down then up).
 */
@Composable
fun DialPad(
    onKeyDown: (Char) -> Unit,
    onKeyUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
                row.forEach { key -> DialKey(key = key, onKeyDown = onKeyDown, onKeyUp = onKeyUp) }
            }
        }
    }
}

@Composable
private fun DialKey(
    key: Char,
    onKeyDown: (Char) -> Unit,
    onKeyUp: () -> Unit,
) {
    val down by rememberUpdatedState(onKeyDown)
    val up by rememberUpdatedState(onKeyUp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(OpenCallerSpacing.dialKey)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .pointerInput(key) {
                    detectTapGestures(
                        onPress = {
                            down(key)
                            tryAwaitRelease()
                            up()
                        },
                    )
                }.semantics {
                    role = Role.Button
                    onClick {
                        down(key)
                        up()
                        true
                    }
                },
    ) {
        Text(text = key.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OpenCallerPreviews
@Composable
private fun DialPadPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DialPad(onKeyDown = {}, onKeyUp = {}, modifier = Modifier)
        }
    }
}
