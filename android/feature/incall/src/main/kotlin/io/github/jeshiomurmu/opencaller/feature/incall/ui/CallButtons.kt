package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing

/** Alpha of a control that can't be used right now. */
private const val DISABLED_ALPHA = 0.38f

/**
 * A large round call action with its label underneath (Answer, Decline, Hang up). The whole column is the touch
 * target, and TalkBack reads the label.
 */
@Composable
internal fun RoundAction(
    @DrawableRes icon: Int,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(min = OpenCallerSpacing.callAction).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(OpenCallerSpacing.callAction).background(container, CircleShape),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = content)
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * One in-call control (Mute, Keypad, Speaker, Hold, Add call, Merge). With [checked] it is a switch that shows its
 * state by fill and to TalkBack; without, a plain button.
 */
@Composable
internal fun ControlButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    enabled: Boolean = true,
) {
    val interaction =
        if (checked != null) {
            Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
        } else {
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        }
    val on = checked == true
    Column(
        modifier = modifier.widthIn(min = OpenCallerSpacing.dialKey).then(interaction).alpha(if (enabled) 1f else DISABLED_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(OpenCallerSpacing.dialKey)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}
