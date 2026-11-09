package io.github.jeshiomurmu.opencaller.core.data.family

import io.github.jeshiomurmu.opencaller.core.data.profile.millisOf
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlert
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.FamilyDay
import io.github.jeshiomurmu.opencaller.core.model.FamilyGroup
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvite
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilyMember
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import io.github.jeshiomurmu.opencaller.core.model.ScamCard
import io.github.jeshiomurmu.opencaller.core.network.FamilyAlertViewDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyInviteDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyInvitePreviewDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyMemberDto
import io.github.jeshiomurmu.opencaller.core.network.ScamCardDto

internal const val ADMIN_ROLE = "admin"

internal fun roleOf(wire: String): FamilyRole = if (wire == ADMIN_ROLE) FamilyRole.OWNER else FamilyRole.MEMBER

internal fun FamilyDto.toModel(): FamilyGroup =
    FamilyGroup(
        id = id,
        name = name,
        adminLabel = adminLabel,
        memberCount = memberCount,
        maxMembers = maxMembers,
        myLabel = me.label,
        myRole = roleOf(me.role),
        mySharing = FamilySharing(otpAlerts = me.shareOtpAlerts, dailyCounts = me.shareDailyCounts),
        consentedAtMillis = millisOf(me.consentedAt),
        members = members.map { it.toModel() },
        alerts = alerts.mapNotNull { it.toFamilyAlert() },
    )

internal fun FamilyGroup.toMembership(): FamilyMembership = FamilyMembership(inGroup = true, isAdmin = isAdmin, sharing = mySharing)

private fun FamilyMemberDto.toModel(): FamilyMember =
    FamilyMember(
        id = id,
        label = label,
        role = roleOf(role),
        joinedAtMillis = millisOf(joinedAt),
        sharing = FamilySharing(otpAlerts = shareOtpAlerts, dailyCounts = shareDailyCounts),
        days = days.map { FamilyDay(it.day, it.blockedCalls, it.spamMessages) },
    )

/** An alert this app understands, or `null` for a kind a newer server added. */
internal fun FamilyAlertViewDto.toFamilyAlert(): FamilyAlert? =
    FamilyAlertKind.parse(kind)?.let { FamilyAlert(id, memberId, memberLabel, it, millisOf(at)) }

internal fun FamilyInviteDto.toModel(): FamilyInvite = FamilyInvite(code, millisOf(expiresAt))

internal fun FamilyInvitePreviewDto.toModel(code: String): FamilyInvitePreview =
    FamilyInvitePreview(code, groupName, adminLabel, members, maxMembers, millisOf(expiresAt), consentVersion)

internal fun ScamCardDto.toScamCard(): ScamCard = ScamCard(id, language, title, body, link, millisOf(updatedAt))
