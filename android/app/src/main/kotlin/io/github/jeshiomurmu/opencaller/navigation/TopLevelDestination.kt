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

/**
 * Messages → a conversation, with [draft] in the composer (a `sms_body` from another app). [recipients] (comma-separated)
 * are the people of a conversation that has no messages yet.
 */
@Serializable
data class ConversationRoute(
    val threadId: Long,
    val draft: String? = null,
    val recipients: String? = null,
)

/** Messages → New message. */
@Serializable
data object ComposeMessageRoute

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

/** Optional sign-in (from the Me tab, a search or a dispute). */
@Serializable
data object AccountSignInRoute

/** Calls → Dial pad, also opened by `ACTION_DIAL` (a tapped `tel:` link, Add call). [number] fills it. */
@Serializable
data class DialerRoute(
    val number: String? = null,
)

/** Calls → Search a number (also Me → Search a number). */
@Serializable
data object NumberSearchRoute

/** Me → Wrongly marked as spam? [number] (E.164) fills the form, e.g. from a search result. */
@Serializable
data class SpamDisputeRoute(
    val number: String? = null,
)

/** Contacts → a contact. */
@Serializable
data class ContactRoute(
    val contactId: Long,
)

/** Contacts → New contact or Edit contact ([contactId] [NEW_CONTACT] for a new one, [number] filled in when given). */
@Serializable
data class ContactEditorRoute(
    val contactId: Long = NEW_CONTACT,
    val number: String? = null,
)

/** [ContactEditorRoute.contactId] of a contact that doesn't exist yet (provider ids start at 1). */
const val NEW_CONTACT: Long = -1L

/** Me → Settings → Quick replies. */
@Serializable
data object QuickRepliesSettingsRoute

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
