package io.github.jeshiomurmu.opencaller.core.data.family

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.FamilyGroup
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvite
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import io.github.jeshiomurmu.opencaller.core.network.CreateFamilyDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyApi
import io.github.jeshiomurmu.opencaller.core.network.FamilyDto
import io.github.jeshiomurmu.opencaller.core.network.FamilySharingDto
import io.github.jeshiomurmu.opencaller.core.network.JoinFamilyDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Family groups as the screens use them ([FamilyRepository]; a fake in tests). */
interface FamilyGroups {
    /** This phone's membership as last seen. */
    val membership: Flow<FamilyMembership>

    /** The user's group from the server, or `null` when they are in none. */
    suspend fun refresh(): AppResult<FamilyGroup?>

    /** Creates a group named [name], with this user as its admin known to members as [label]. */
    suspend fun create(
        name: String,
        label: String,
    ): AppResult<FamilyGroup>

    /** A new single-use invite code (admin only). */
    suspend fun invite(): AppResult<FamilyInvite>

    /** What joining with [code] means; changes nothing. */
    suspend fun preview(code: String): AppResult<FamilyInvitePreview>

    /** Joins [invite]'s group as [label] sharing [sharing], only with the member's own [consented] tick. */
    suspend fun join(
        invite: FamilyInvitePreview,
        label: String,
        sharing: FamilySharing,
        consented: Boolean,
    ): AppResult<FamilyGroup>

    /** Changes this member's [label] and what they share. */
    suspend fun update(
        label: String,
        sharing: FamilySharing,
    ): AppResult<FamilyGroup>

    /** Leaves the group; the admin leaving ends it. */
    suspend fun leave(): AppResult<Unit>

    /** The admin removes member [memberId]; returns the group afterwards. */
    suspend fun remove(memberId: String): AppResult<FamilyGroup?>
}

/**
 * Family groups (docs/prd.md F-PR-14, ADR 0008). The group lives on the server; this phone remembers only its own
 * membership ([FamilyPreferences]) and keeps the daily counts worker scheduled exactly while the member shares
 * counts. A member joins only from their own phone, with an invite code, after the consent screen.
 */
@Singleton
class FamilyRepository
    @Inject
    constructor(
        private val api: FamilyApi,
        private val preferences: FamilyPreferences,
        private val work: FamilyWork,
    ) : FamilyGroups {
        override val membership: Flow<FamilyMembership> = preferences.membership

        /** The user's group from the server, or `null` when they are in none. */
        override suspend fun refresh(): AppResult<FamilyGroup?> =
            when (val result = apiCall { api.family() }) {
                is AppResult.Success -> {
                    AppResult.Success(remember(result.value))
                }

                is AppResult.Failure -> {
                    if (result.error == AppError.NotFound) {
                        forget()
                        AppResult.Success(null)
                    } else {
                        result
                    }
                }
            }

        /** Creates a group named [name], with this user as its admin known to members as [label]. */
        override suspend fun create(
            name: String,
            label: String,
        ): AppResult<FamilyGroup> = apiCall { api.create(CreateFamilyDto(name.trim(), label.trim())) }.map { remember(it) }

        /** A new single-use invite code (admin only). */
        override suspend fun invite(): AppResult<FamilyInvite> = apiCall { api.invite() }.map { it.toModel() }

        /** What joining with [code] means; changes nothing. A code that can't be one fails without a request. */
        override suspend fun preview(code: String): AppResult<FamilyInvitePreview> {
            val normalized = normalizeCode(code) ?: return AppResult.Failure(AppError.Validation(INVALID_CODE))
            return apiCall { api.preview(normalized) }.map { it.toModel(normalized) }
        }

        /**
         * Joins the group of [invite] as [label], sharing [sharing]. [consented] is the member's own tick on the
         * consent screen; without it nothing is sent.
         */
        override suspend fun join(
            invite: FamilyInvitePreview,
            label: String,
            sharing: FamilySharing,
            consented: Boolean,
        ): AppResult<FamilyGroup> {
            if (!consented) return AppResult.Failure(AppError.Validation(CONSENT_REQUIRED))
            val body =
                JoinFamilyDto(
                    code = invite.code,
                    label = label.trim(),
                    consent = true,
                    consentVersion = FamilySharing.CONSENT_VERSION,
                    shareOtpAlerts = sharing.otpAlerts,
                    shareDailyCounts = sharing.dailyCounts,
                )
            return apiCall { api.join(body) }.map { remember(it) }
        }

        /** Changes this member's [label] and what they share; switching something off deletes it on the server. */
        override suspend fun update(
            label: String,
            sharing: FamilySharing,
        ): AppResult<FamilyGroup> =
            apiCall { api.updateMe(FamilySharingDto(label.trim(), sharing.otpAlerts, sharing.dailyCounts)) }.map { remember(it) }

        /** Leaves the group; the admin leaving ends it for everyone. Already out of a group counts as left. */
        override suspend fun leave(): AppResult<Unit> =
            when (val result = apiCallNoContent { api.leave() }) {
                is AppResult.Success -> {
                    forget()
                    result
                }

                is AppResult.Failure -> {
                    if (result.error == AppError.NotFound) {
                        forget()
                        AppResult.Success(Unit)
                    } else {
                        result
                    }
                }
            }

        /** The admin removes member [memberId]; returns the group afterwards. */
        override suspend fun remove(memberId: String): AppResult<FamilyGroup?> =
            when (val removed = apiCallNoContent { api.remove(memberId) }) {
                is AppResult.Success -> refresh()
                is AppResult.Failure -> removed
            }

        private suspend fun remember(dto: FamilyDto): FamilyGroup {
            val group = dto.toModel()
            val membership = group.toMembership()
            preferences.set(membership)
            work.scheduleDailyCounts(membership.sendsCounts)
            return group
        }

        private suspend fun forget() {
            preferences.set(FamilyMembership.NONE)
            work.scheduleDailyCounts(false)
        }

        /** Problem codes of checks made on the phone, and the invite code's format. */
        companion object {
            /** The code can't be an invite (wrong length or letters). */
            const val INVALID_CODE = "invalid_invite_code"

            /** The member didn't tick the consent box. */
            const val CONSENT_REQUIRED = "consent_required"

            /** Letters and digits an invite code uses (no look-alikes such as 0/O, 1/I). */
            const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

            /** Characters in a code. */
            const val CODE_LENGTH = 8

            /** [code] as the server expects it (upper case, without spaces or dashes), or `null` if it can't be one. */
            fun normalizeCode(code: String): String? =
                code
                    .filterNot { it.isWhitespace() || it == '-' }
                    .uppercase()
                    .takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
        }
    }
