package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `POST /v1/family` body. */
@Serializable
data class CreateFamilyDto(
    val name: String,
    val label: String,
)

/** `POST /v1/family/join` body; [consent] must be `true` and [consentVersion] the wording the app showed. */
@Serializable
data class JoinFamilyDto(
    val code: String,
    val label: String,
    val consent: Boolean,
    val consentVersion: Int,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
)

/** `PUT /v1/family/me` body. */
@Serializable
data class FamilySharingDto(
    val label: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
)

/** `POST /v1/family/alerts` body: `otp_during_call` or `otp_burst`. */
@Serializable
data class FamilyAlertDto(
    val kind: String,
)

/** A stored alert and how many of the admin's phones its push reached. */
@Serializable
data class FamilyAlertSentDto(
    val id: String,
    val reachedDevices: Int = 0,
)

/** `PUT /v1/family/counts/{day}` body. */
@Serializable
data class FamilyCountsDto(
    val blockedCalls: Int,
    val spamMessages: Int,
)

/** A new invite code, shown once. */
@Serializable
data class FamilyInviteDto(
    val code: String,
    val expiresAt: String,
)

/** What joining with an invite means. */
@Serializable
data class FamilyInvitePreviewDto(
    val groupName: String,
    val adminLabel: String,
    val members: Int,
    val maxMembers: Int,
    val expiresAt: String,
    val consentVersion: Int,
)

/** This user's own membership. */
@Serializable
data class FamilyMeDto(
    val label: String,
    val role: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
    val consentedAt: String,
    val consentVersion: Int,
)

/** One day of a member's counts. */
@Serializable
data class FamilyDayCountsDto(
    val day: String,
    val blockedCalls: Int,
    val spamMessages: Int,
)

/** A member as the admin sees them. */
@Serializable
data class FamilyMemberDto(
    val id: String,
    val label: String,
    val role: String,
    val joinedAt: String,
    val shareOtpAlerts: Boolean,
    val shareDailyCounts: Boolean,
    val days: List<FamilyDayCountsDto> = emptyList(),
)

/** An alert as the admin sees it. */
@Serializable
data class FamilyAlertViewDto(
    val id: String,
    val memberId: String,
    val memberLabel: String,
    val kind: String,
    val at: String,
)

/** `GET /v1/family`: [members] and [alerts] are filled for the admin only. */
@Serializable
data class FamilyDto(
    val id: String,
    val name: String,
    val adminLabel: String,
    val memberCount: Int,
    val maxMembers: Int,
    val me: FamilyMeDto,
    val members: List<FamilyMemberDto> = emptyList(),
    val alerts: List<FamilyAlertViewDto> = emptyList(),
)

/** One scam card. */
@Serializable
data class ScamCardDto(
    val id: String,
    val language: String,
    val title: String,
    val body: String,
    val link: String? = null,
    val updatedAt: String,
)

/** `GET /v1/scam-feed/{iso}`. */
@Serializable
data class ScamFeedDto(
    val country: String,
    val cards: List<ScamCardDto> = emptyList(),
)
