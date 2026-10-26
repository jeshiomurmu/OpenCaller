package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** The twelve keys of a phone keypad, row by row. */
private val KEY_ROWS = listOf("123", "456", "789", "*0#")

/** The letters under each key (ITU E.161), for T9 search; `+` under 0, its long-press. */
private val KEY_LETTERS =
    mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")

/**
 * A phone keypad (dial pad and in-call keypad). [onKeyDown] fires when a key is pressed and [onKeyUp] when it is
 * released, so a call's keypad tone lasts as long as the press. TalkBack activates a key with one double tap
 * (down then up). Keys in [longPressLabels] also have a long press ([onKeyLongPress], after the key's own
 * [onKeyDown]), e.g. voicemail on 1; the label is what TalkBack announces for it.
 */
@Composable
fun DialPad(
    onKeyDown: (Char) -> Unit,
    onKeyUp: () -> Unit,
    modifier: Modifier = Modifier,
    longPressLabels: Map<Char, String> = emptyMap(),
    onKeyLongPress: (Char) -> Unit = {},
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
                row.forEach { key ->
                    DialKey(
                        key = key,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp,
                        longPressLabel = longPressLabels[key],
                        onLongPress = onKeyLongPress,
                    )
                }
            }
        }
    }
}

// Touch: down while pressed (a DTMF tone during a call), and the long press when there is one. TalkBack: a click, and
// the long press as a named action. A long press follows the key's own press, as on the AOSP dialer.
private fun Modifier.keyInput(
    key: Char,
    longPressLabel: String?,
    down: State<(Char) -> Unit>,
    up: State<() -> Unit>,
    long: State<(Char) -> Unit>,
): Modifier =
    pointerInput(key, longPressLabel != null) {
        detectTapGestures(
            onPress = {
                down.value(key)
                tryAwaitRelease()
                up.value()
            },
            onLongPress = if (longPressLabel != null) ({ long.value(key) }) else null,
        )
    }.semantics(mergeDescendants = true) {
        role = Role.Button
        onClick {
            down.value(key)
            up.value()
            true
        }
        longPressLabel?.let { label ->
            onLongClick(label) {
                down.value(key)
                up.value()
                long.value(key)
                true
            }
        }
    }

@Composable
private fun DialKey(
    key: Char,
    onKeyDown: (Char) -> Unit,
    onKeyUp: () -> Unit,
    longPressLabel: String?,
    onLongPress: (Char) -> Unit,
) {
    val down = rememberUpdatedState(onKeyDown)
    val up = rememberUpdatedState(onKeyUp)
    val long = rememberUpdatedState(onLongPress)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .size(OpenCallerSpacing.dialKey)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .keyInput(key, longPressLabel, down, up, long),
    ) {
        Text(text = key.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KEY_LETTERS[key]?.let { letters ->
            Text(
                text = letters,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
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
