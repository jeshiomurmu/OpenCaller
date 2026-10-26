package io.github.jeshiomurmu.opencaller.feature.contacts

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing

/**
 * The letters down the side of the contacts list: tap or drag to jump to a letter. TalkBack users get each letter as
 * its own "Go to …" action.
 */
@Composable
internal fun FastScroller(
    letters: List<String>,
    onLetter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val jump by rememberUpdatedState(onLetter)
    val count = letters.size
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxHeight()
                .widthIn(min = OpenCallerSpacing.l)
                .padding(vertical = OpenCallerSpacing.xs)
                .pointerInput(count) {
                    detectTapGestures { offset -> jump(indexAt(offset.y, size.height.toFloat(), count)) }
                }.pointerInput(count) {
                    detectVerticalDragGestures { change, _ -> jump(indexAt(change.position.y, size.height.toFloat(), count)) }
                },
    ) {
        letters.forEachIndexed { index, letter ->
            val label = stringResource(R.string.contacts_go_to, letter)
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.semantics {
                        contentDescription = label
                        onClick {
                            jump(index)
                            true
                        }
                    },
            )
        }
    }
}

// The letter under a touch at height y of a column of the given height.
private fun indexAt(
    y: Float,
    height: Float,
    count: Int,
): Int = if (height <= 0f) 0 else ((y / height) * count).toInt().coerceIn(0, count - 1)
