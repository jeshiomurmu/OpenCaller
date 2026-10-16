package io.github.jeshiomurmu.opencaller.core.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.jeshiomurmu.opencaller.core.model.AccessRequestOutcome

/**
 * Starts system permission dialogs, role dialogs and settings screens and reports an [AccessRequestOutcome].
 * Only call it from the Continue action of a [PermissionExplainer] (docs/design.md §7).
 */
class AccessRequestLauncher internal constructor(
    private val permissions: ManagedActivityResultLauncher<String, Boolean>,
    private val screens: ManagedActivityResultLauncher<Intent, ActivityResult>,
    private val onPermissionRequested: (String) -> Unit,
    private val onUnavailable: (ActivityNotFoundException) -> Unit,
) {
    /** Shows the runtime-permission dialog for [permission]. */
    fun requestPermission(permission: String) {
        onPermissionRequested(permission)
        permissions.launch(permission)
    }

    /** Starts [intent] (a role dialog or a settings page) for a result. */
    fun openScreen(intent: Intent) {
        try {
            screens.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Some devices ship without a given settings page; report it so the caller can tell the user.
            onUnavailable(e)
        }
    }
}

/** Remembers an [AccessRequestLauncher] that survives configuration changes and process death. */
@Composable
fun rememberAccessRequestLauncher(onResult: (AccessRequestOutcome) -> Unit): AccessRequestLauncher {
    val activity = LocalActivity.current
    val currentOnResult by rememberUpdatedState(onResult)
    var lastPermission by rememberSaveable { mutableStateOf<String?>(null) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            currentOnResult(permissionOutcome(granted, activity, lastPermission))
        }
    val screens =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Settings pages always return RESULT_CANCELED, so callers re-read the real status either way.
            currentOnResult(if (result.resultCode == Activity.RESULT_OK) AccessRequestOutcome.GRANTED else AccessRequestOutcome.DENIED)
        }
    return remember(permissions, screens) {
        AccessRequestLauncher(
            permissions = permissions,
            screens = screens,
            onPermissionRequested = { lastPermission = it },
            onUnavailable = { currentOnResult(AccessRequestOutcome.UNAVAILABLE) },
        )
    }
}

// After a denial, Android stops showing its dialog once shouldShowRequestPermissionRationale turns false.
private fun permissionOutcome(
    granted: Boolean,
    activity: Activity?,
    permission: String?,
): AccessRequestOutcome =
    when {
        granted -> {
            AccessRequestOutcome.GRANTED
        }

        activity != null && permission != null && !activity.shouldShowRequestPermissionRationale(permission) -> {
            AccessRequestOutcome.BLOCKED
        }

        else -> {
            AccessRequestOutcome.DENIED
        }
    }
