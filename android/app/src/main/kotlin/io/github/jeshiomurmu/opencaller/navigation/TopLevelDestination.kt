package io.github.jeshiomurmu.opencaller.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import kotlinx.serialization.Serializable

/** Type-safe route of the Calls tab. */
@Serializable
data object CallsRoute

/** Type-safe route of the Messages tab. */
@Serializable
data object MessagesRoute

/** Type-safe route of the Contacts tab. */
@Serializable
data object ContactsRoute

/** Type-safe route of the Protection tab. */
@Serializable
data object ProtectionRoute

/** Type-safe route of the Me tab. */
@Serializable
data object MeRoute

/**
 * Settings → Permissions & roles (under the Me tab). [explain] is an `AccessItem` name whose explainer opens
 * right away, e.g. from the degraded-mode banner's Fix.
 */
@Serializable
data class PermissionsRoute(
    val explain: String? = null,
)

/** Protection → Your rules. */
@Serializable
data object BlockingRulesRoute

/** Protection → Blocked calls. */
@Serializable
data object BlockedCallsRoute

/** Me → Settings → About → version ×7. */
@Serializable
data object ScreeningDebugRoute

/** The five bottom-bar tabs (docs/design.md §6), in display order. */
enum class TopLevelDestination(
    val route: Any,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:StringRes val placeholder: Int,
) {
    CALLS(CallsRoute, R.string.tab_calls, OpenCallerIcons.Calls, R.string.placeholder_calls),
    MESSAGES(MessagesRoute, R.string.tab_messages, OpenCallerIcons.Messages, R.string.placeholder_messages),
    CONTACTS(ContactsRoute, R.string.tab_contacts, OpenCallerIcons.Contacts, R.string.placeholder_contacts),
    PROTECTION(ProtectionRoute, R.string.tab_protection, OpenCallerIcons.Protection, R.string.placeholder_protection),
    ME(MeRoute, R.string.tab_me, OpenCallerIcons.Me, R.string.placeholder_me),
}
