package io.github.jeshiomurmu.opencaller.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import timber.log.Timber

/**
 * The server refused this app version (426, docs/security.md SEC-6). Update opens the app's page in the store app
 * that handles `market://` links (Google Play or an F-Droid client); Later hides the dialog until the next start.
 */
@Composable
internal fun UpdateRequiredDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.update_required_title)) },
        text = { Text(stringResource(R.string.update_required_body)) },
        confirmButton = {
            TextButton(onClick = {
                openStorePage(context)
                onDismiss()
            }) { Text(stringResource(R.string.update_required_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_required_later)) } },
    )
}

private fun openStorePage(context: Context) {
    val intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No store app handles market:// links")
    }
}

@OpenCallerPreviews
@Composable
private fun UpdateRequiredDialogPreview() {
    OpenCallerTheme { UpdateRequiredDialog(onDismiss = {}) }
}
