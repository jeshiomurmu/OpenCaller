package io.github.jeshiomurmu.opencaller.feature.messages.ui

import android.graphics.ImageDecoder
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.feature.messages.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** One message: the user's own on the right, others' on the left, with pictures, a download button and its status. */
@Composable
internal fun MessageBubble(
    item: ThreadItem,
    showSender: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (showSender && !item.outgoing && item.sender != null) {
            Text(text = item.sender, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Surface(
            shape = OpenCallerShape.card,
            color = if (item.outgoing) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (item.outgoing) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(BUBBLE_FRACTION).wrapContentWidth(if (item.outgoing) Alignment.End else Alignment.Start),
        ) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.s), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                item.subject?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
                item.images.forEach {
                    PartImage(uri = it, contentDescription = stringResource(R.string.messages_picture), modifier = Modifier.fillMaxWidth())
                }
                if (item.download != MmsDownload.DONE) DownloadLine(item, onDownload)
                if (item.body.isNotBlank()) Text(text = item.body, style = MaterialTheme.typography.bodyLarge)
            }
        }
        StatusLine(item = item, onRetry = onRetry)
    }
}

@Composable
private fun DownloadLine(
    item: ThreadItem,
    onDownload: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        when (item.download) {
            MmsDownload.DOWNLOADING -> {
                CircularProgressIndicator(modifier = Modifier.size(OpenCallerSpacing.iconSmall))
                Text(text = stringResource(R.string.messages_downloading), style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                Text(
                    text =
                        stringResource(
                            if (item.download ==
                                MmsDownload.FAILED
                            ) {
                                R.string.messages_download_failed
                            } else {
                                R.string.messages_download_waiting
                            },
                            size,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onDownload) { Text(stringResource(R.string.messages_download)) }
            }
        }
    }
}

@Composable
private fun StatusLine(
    item: ThreadItem,
    onRetry: () -> Unit,
) {
    val time =
        DateUtils.formatDateTime(
            LocalContext.current,
            item.atMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
    val status =
        when (item.status) {
            MessageStatus.NONE -> null
            MessageStatus.SENDING -> stringResource(R.string.messages_status_sending)
            MessageStatus.SENT -> stringResource(R.string.messages_status_sent)
            MessageStatus.DELIVERED -> stringResource(R.string.messages_status_delivered)
            MessageStatus.FAILED -> stringResource(R.string.messages_status_failed)
        }
    val failed = item.status == MessageStatus.FAILED
    val canRetry = failed && item.key.kind == MessageKind.SMS
    Text(
        text = listOfNotNull(time, status).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(horizontal = OpenCallerSpacing.xxs, vertical = OpenCallerSpacing.xxs)
                .then(
                    if (canRetry) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.messages_retry),
                            onClick = onRetry,
                        )
                    } else {
                        Modifier
                    },
                ),
    )
}

/**
 * A picture from Android's message store or the photo picker ([uri] is a `content://` address), decoded at most
 * [IMAGE_SIDE] pixels on its longer side.
 */
@Composable
internal fun PartImage(
    uri: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // A composable, not a class: there is no DispatcherProvider to inject here (docs/rules.md §4 is about classes).
    val bitmap by produceState<ImageBitmap?>(null, uri) { value = withContext(Dispatchers.IO) { decode(context.contentResolver, uri) } }
    val clickable =
        if (onClick !=
            null
        ) {
            Modifier.clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
        } else {
            Modifier
        }
    val shown = bitmap
    if (shown == null) {
        Box(
            modifier =
                modifier
                    .size(
                        PLACEHOLDER,
                    ).clip(OpenCallerShape.chip)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(clickable),
        )
    } else {
        Image(
            bitmap = shown,
            contentDescription = contentDescription,
            contentScale = if (onClick != null) ContentScale.Crop else ContentScale.FillWidth,
            modifier = modifier.clip(OpenCallerShape.chip).then(clickable),
        )
    }
}

private fun decode(
    resolver: android.content.ContentResolver,
    uri: String,
): ImageBitmap? =
    try {
        ImageDecoder
            .decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(uri))) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > IMAGE_SIDE) {
                    val scale = IMAGE_SIDE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }.asImageBitmap()
    } catch (e: IOException) {
        Timber.w(e, "Picture not readable")
        null
    } catch (e: SecurityException) {
        Timber.w(e, "Picture not allowed")
        null
    }

private const val BUBBLE_FRACTION = 0.8f
private val PLACEHOLDER = OpenCallerSpacing.xxl * 2
private const val IMAGE_SIDE = 1080
