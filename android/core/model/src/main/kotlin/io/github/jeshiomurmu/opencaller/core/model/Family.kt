package io.github.jeshiomurmu.opencaller.core.model

/** What the OTP guard tells a family admin, by its server name (docs/prd.md F-PR-14, ADR 0008). */
enum class FamilyAlertKind(
    val wireName: String,
) {
    /** A one-time code arrived during, or just after, a call with a stranger. */
    OTP_DURING_CALL("otp_during_call"),

    /** Several one-time codes from different senders arrived within minutes. */
    OTP_BURST("otp_burst"),
    ;

    /** Lookup by the server's name. */
    companion object {
        /** The kind named [value], or `null` for one this app doesn't know (a newer server). */
        fun parse(value: String?): FamilyAlertKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** What a member shares with their group's admin; both are off unless the member switched them on. */
data class FamilySharing(
    val otpAlerts: Boolean,
    val dailyCounts: Boolean,
) {
    /** Limits and the consent wording. */
    companion object {
        /** Nothing shared (the admin's own setting, and the state outside a group). */
        val NONE = FamilySharing(otpAlerts = false, dailyCounts = false)

        /** The version of the consent screen's wording; the server records it with the member's consent. */
        const val CONSENT_VERSION = 1

        /** People in a group, the admin included. */
        const val MAX_MEMBERS = 5

        /** Longest label ("Mum", "Grandpa"). */
        const val MAX_LABEL = 40

        /** Days of counts the admin sees. */
        const val SUMMARY_DAYS = 7
    }
}

/** One day of a member's counts; [day] is `yyyy-mm-dd`. */
data class FamilyDay(
    val day: String,
    val blockedCalls: Int,
    val spamMessages: Int,
)

/** A member as the admin sees them: a label, what they share, and the days they shared. */
data class FamilyMember(
    val id: String,
    val label: String,
    val role: FamilyRole,
    val joinedAtMillis: Long,
    val sharing: FamilySharing,
    val days: List<FamilyDay>,
)

/** An alert as the admin sees it: who (their label), what kind, when. No number, no message. */
data class FamilyAlert(
    val id: String,
    val memberId: String,
    val memberLabel: String,
    val kind: FamilyAlertKind,
    val atMillis: Long,
)

/**
 * The user's family group. Everyone sees the group, its admin's label and their own membership; only the admin gets
 * [members] (with the counts they share) and [alerts].
 */
data class FamilyGroup(
    val id: String,
    val name: String,
    val adminLabel: String,
    val memberCount: Int,
    val maxMembers: Int,
    val myLabel: String,
    val myRole: FamilyRole,
    val mySharing: FamilySharing,
    val consentedAtMillis: Long,
    val members: List<FamilyMember>,
    val alerts: List<FamilyAlert>,
) {
    /** True for the group's admin. */
    val isAdmin: Boolean get() = myRole == FamilyRole.OWNER

    /** True while another person can join. */
    val hasRoom: Boolean get() = memberCount < maxMembers
}

/** A single-use invite code the admin passes on outside the app; valid until [expiresAtMillis]. */
data class FamilyInvite(
    val code: String,
    val expiresAtMillis: Long,
)

/** What joining with an invite means, shown on the consent screen before anything is shared. */
data class FamilyInvitePreview(
    val code: String,
    val groupName: String,
    val adminLabel: String,
    val members: Int,
    val maxMembers: Int,
    val expiresAtMillis: Long,
    val consentVersion: Int,
)

/** An admin-curated warning about a current scam in the user's country (docs/prd.md F-PR-15). */
data class ScamCard(
    val id: String,
    val language: String,
    val title: String,
    val body: String,
    val link: String?,
    val updatedAtMillis: Long,
)
