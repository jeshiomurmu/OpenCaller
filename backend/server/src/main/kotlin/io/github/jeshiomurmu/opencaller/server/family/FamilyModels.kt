package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** `POST /v1/family`: a new group named [name]; [label] is how its members see the admin (e.g. "Mum"). */
@Serializable
data class CreateFamilyRequest(
    val name: String,
    val label: String,
)

/**
 * `POST /v1/family/join`: join with invite [code] as [label]. [consent] must be `true` and [consentVersion] the
 * wording the app showed ([FamilyRules.CONSENT_VERSION]); the two switches are what this member shares.
 */
@Serializable
data class JoinFamilyRequest(
    val code: String,
    val label: String,
    val consent: Boolean,
    val consentVersion: Int,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
)

/** `PUT /v1/family/me`: this member's label and what they share. */
@Serializable
data class FamilySharingRequest(
    val label: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
)

/** `POST /v1/family/alerts`: [kind] is `otp_during_call` or `otp_burst`; nothing else is sent. */
@Serializable
data class FamilyAlertRequest(
    val kind: String,
)

/** A stored alert; [reachedDevices] is how many of the admin's phones the push reached. */
@Serializable
data class FamilyAlertSent(
    val id: String,
    val reachedDevices: Int,
)

/** `PUT /v1/family/counts/{day}`: this member's counts for one day. */
@Serializable
data class FamilyCountsRequest(
    val blockedCalls: Int,
    val spamMessages: Int,
)

/** A new invite: the [code] is shown once (the server keeps only its hash). */
@Serializable
data class FamilyInvite(
    val code: String,
    val expiresAt: String,
)

/**
 * What joining with an invite means, shown on the consent screen before anything is shared: the group, its admin's
 * label, how many people are in it, and the wording version the server expects.
 */
@Serializable
data class FamilyInvitePreview(
    val groupName: String,
    val adminLabel: String,
    val members: Int,
    val maxMembers: Int,
    val expiresAt: String,
    val consentVersion: Int,
)

/** This user's own membership. */
@Serializable
data class FamilyMe(
    val label: String,
    val role: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
    val consentedAt: String,
    val consentVersion: Int,
)

/** One day of a member's counts. */
@Serializable
data class FamilyDayCounts(
    val day: String,
    val blockedCalls: Int,
    val spamMessages: Int,
)

/** A member as the admin sees them; [days] holds the last 7 days the member shared (none if they don't share). */
@Serializable
data class FamilyMemberView(
    val id: String,
    val label: String,
    val role: String,
    val joinedAt: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
    val days: List<FamilyDayCounts> = emptyList(),
)

/** An alert as the admin sees it: who (their label), what kind, when. */
@Serializable
data class FamilyAlertView(
    val id: String,
    val memberId: String,
    val memberLabel: String,
    val kind: String,
    val at: String,
)

/**
 * `GET /v1/family`. Everyone sees the group, its admin's label and their own membership ([me]); only the admin sees
 * [members] (with the counts they share) and [alerts] (last 30 days).
 */
@Serializable
data class FamilyView(
    val id: String,
    val name: String,
    val adminLabel: String,
    val memberCount: Int,
    val maxMembers: Int,
    val me: FamilyMe,
    val members: List<FamilyMemberView> = emptyList(),
    val alerts: List<FamilyAlertView> = emptyList(),
)

/** Limits, kinds and field rules of family protection (docs/prd.md F-PR-14, ADR 0008). */
object FamilyRules {
    /** Members of a group, the admin included. */
    const val MAX_MEMBERS = 5

    /** The consent wording the app shows; a join quoting another version is refused. */
    const val CONSENT_VERSION = 1

    /** Longest group name or label. */
    const val MAX_LABEL = 40

    /** Days of counts the admin sees. */
    const val SUMMARY_DAYS = 7L

    /** Days alerts are kept. */
    const val ALERT_DAYS = 30L

    /** Hours an invite stays valid. */
    const val INVITE_HOURS = 48L

    /** Largest count a day may have. */
    const val MAX_COUNT = 10_000

    /** Alert kinds a member's phone may raise. */
    val ALERT_KINDS = setOf("otp_during_call", "otp_burst")

    /** `admin` / `member` (`family_members.role`). */
    const val ADMIN = "admin"
    const val MEMBER = "member"

    /** Checks a name or label: 1 to [MAX_LABEL] characters on one line, no surrounding spaces. */
    fun checkLabel(
        value: String,
        field: String,
    ) {
        requireValid(value.trim() == value && '\n' !in value && isPlainText(value, MAX_LABEL)) {
            invalidFamily("$field must be 1 to $MAX_LABEL characters on one line.")
        }
    }
}

/** 400 `invalid_request` for a family request. */
internal fun invalidFamily(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Invalid family request", detail)

/** 404 `not_in_family`. */
internal fun notInFamily() = ApiProblem(HttpStatusCode.NotFound, "not_in_family", "You're not in a family group")

/** 403 `family_admin_only`. */
internal fun adminOnly() = ApiProblem(HttpStatusCode.Forbidden, "family_admin_only", "Only the group's admin can do this")

/** 409 `already_in_family`. */
internal fun alreadyInFamily() =
    ApiProblem(HttpStatusCode.Conflict, "already_in_family", "You're already in a family group", "Leave it first.")

/** 404 `invite_not_found`: unknown, used and expired codes all answer the same. */
internal fun inviteNotFound() =
    ApiProblem(
        HttpStatusCode.NotFound,
        "invite_not_found",
        "This invite code doesn't work",
        "Ask for a new code; codes work once, for 48 hours.",
    )

/** 409 `family_full`. */
internal fun familyFull() =
    ApiProblem(
        HttpStatusCode.Conflict,
        "family_full",
        "This family group is full",
        "A group has at most ${FamilyRules.MAX_MEMBERS} people.",
    )

/** 403 `not_shared`: the member turned this kind of sharing off. */
internal fun notShared() = ApiProblem(HttpStatusCode.Forbidden, "not_shared", "You don't share this with your family group")
