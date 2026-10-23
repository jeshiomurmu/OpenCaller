package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItem

/**
 * The [PermissionExplainer] for an [AccessItem], with the same copy in onboarding and in
 * Settings → Permissions & roles.
 *
 * @param opensSettings true when Continue leaves the app for a settings page (button says "Open settings").
 */
@Composable
fun AccessItemExplainer(
    item: AccessItem,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    progress: String? = null,
    opensSettings: Boolean = false,
) {
    val copy = item.explainerCopy()
    PermissionExplainer(
        icon = copy.icon,
        title = stringResource(copy.title),
        why = stringResource(copy.why),
        never = stringResource(copy.never),
        onContinue = onContinue,
        onNotNow = onNotNow,
        modifier = modifier,
        progress = progress,
        continueLabel = stringResource(if (opensSettings) R.string.explainer_open_settings else R.string.explainer_continue),
    )
}

/** Short name of [AccessItem], e.g. "Call screening". */
@StringRes
fun AccessItem.labelRes(): Int =
    when (this) {
        AccessItem.CALL_SCREENING -> R.string.access_call_screening
        AccessItem.CONTACTS -> R.string.access_contacts
        AccessItem.NOTIFICATIONS -> R.string.access_notifications
        AccessItem.DEFAULT_PHONE_APP -> R.string.access_default_phone_app
        AccessItem.DEFAULT_SMS_APP -> R.string.access_default_sms_app
        AccessItem.OVERLAY -> R.string.access_overlay
        AccessItem.CALL_STATE -> R.string.access_call_state
        AccessItem.FULL_SCREEN_CALLS -> R.string.access_full_screen_calls
    }

/** Icon of [AccessItem]. */
@DrawableRes
fun AccessItem.iconRes(): Int = explainerCopy().icon

private data class ExplainerCopy(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    @param:StringRes val never: Int,
)

private val explainerCopies: Map<AccessItem, ExplainerCopy> =
    mapOf(
        AccessItem.CALL_SCREENING to
            ExplainerCopy(
                OpenCallerIcons.Protection,
                R.string.explain_call_screening_title,
                R.string.explain_call_screening_why,
                R.string.explain_call_screening_never,
            ),
        AccessItem.CONTACTS to
            ExplainerCopy(
                OpenCallerIcons.Contacts,
                R.string.explain_contacts_title,
                R.string.explain_contacts_why,
                R.string.explain_contacts_never,
            ),
        AccessItem.NOTIFICATIONS to
            ExplainerCopy(
                OpenCallerIcons.Notifications,
                R.string.explain_notifications_title,
                R.string.explain_notifications_why,
                R.string.explain_notifications_never,
            ),
        AccessItem.DEFAULT_PHONE_APP to
            ExplainerCopy(OpenCallerIcons.Calls, R.string.explain_dialer_title, R.string.explain_dialer_why, R.string.explain_dialer_never),
        AccessItem.DEFAULT_SMS_APP to
            ExplainerCopy(OpenCallerIcons.Messages, R.string.explain_sms_title, R.string.explain_sms_why, R.string.explain_sms_never),
        AccessItem.OVERLAY to
            ExplainerCopy(
                OpenCallerIcons.Overlay,
                R.string.explain_overlay_title,
                R.string.explain_overlay_why,
                R.string.explain_overlay_never,
            ),
        AccessItem.CALL_STATE to
            ExplainerCopy(
                OpenCallerIcons.Device,
                R.string.explain_call_state_title,
                R.string.explain_call_state_why,
                R.string.explain_call_state_never,
            ),
        AccessItem.FULL_SCREEN_CALLS to
            ExplainerCopy(
                OpenCallerIcons.Privacy,
                R.string.explain_full_screen_calls_title,
                R.string.explain_full_screen_calls_why,
                R.string.explain_full_screen_calls_never,
            ),
    )

private fun AccessItem.explainerCopy(): ExplainerCopy = explainerCopies.getValue(this)

@OpenCallerPreviews
@Composable
private fun AccessItemExplainerPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AccessItemExplainer(item = AccessItem.CALL_SCREENING, onContinue = {}, onNotNow = {}, progress = "Step 3 of 5")
        }
    }
}
