package io.github.jeshiomurmu.opencaller.feature.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing

/** A profile picture from JPEG bytes, or a person icon; [description] is read by TalkBack. */
@Composable
fun ProfilePicture(
    picture: PictureBytes?,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(picture) { picture?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() } }
    Box(
        modifier = modifier.size(PICTURE_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(PICTURE_SIZE),
            )
        } else {
            Icon(
                painter = painterResource(OpenCallerIcons.Me),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PICTURE_SIZE / 2),
            )
        }
    }
}

/** A tappable row: title, summary, optional trailing text (e.g. a count). */
@Composable
fun ProfileRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .then(clickable)
                .padding(vertical = OpenCallerSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.let { Text(text = it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

/** The message for [problem]. */
@Composable
fun problemText(problem: ProfileProblem): String =
    stringResource(
        when (problem) {
            ProfileProblem.OFFLINE -> R.string.problem_offline
            ProfileProblem.SIGNED_OUT -> R.string.profile_problem_signed_out
            ProfileProblem.NOT_CONNECTED -> R.string.not_connected_body
            ProfileProblem.PICTURE -> R.string.profile_problem_picture
            ProfileProblem.SERVER -> R.string.problem_server
        },
    )

/** Error text in the error colour. */
@Composable
fun ProblemLine(
    problem: ProfileProblem?,
    modifier: Modifier = Modifier,
) {
    problem?.let {
        Text(
            text = problemText(it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
    }
}

private val PICTURE_SIZE = OpenCallerSpacing.profilePicture
