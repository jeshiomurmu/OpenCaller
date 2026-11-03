package io.github.jeshiomurmu.opencaller.core.data.business

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessClaim
import io.github.jeshiomurmu.opencaller.core.model.BusinessNumber
import io.github.jeshiomurmu.opencaller.core.model.BusinessProfile
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind
import io.github.jeshiomurmu.opencaller.core.model.ClaimForm
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.BusinessApi
import io.github.jeshiomurmu.opencaller.core.network.ClaimCodeDto
import io.github.jeshiomurmu.opencaller.core.network.ClaimDto
import io.github.jeshiomurmu.opencaller.core.network.ClaimUploadRequestDto
import io.github.jeshiomurmu.opencaller.core.network.ResendCodeDto
import io.github.jeshiomurmu.opencaller.core.network.StartClaimDto
import io.github.jeshiomurmu.opencaller.core.network.SubmitClaimDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** A file picked for a claim: its bytes and MIME type. */
class ClaimFile(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * Business claims (docs/prd.md F-BZ-01): the claimant enters the business, proves control of its number with a
 * code sent there (SMS or a phone call), uploads proof documents and an optional logo, and submits for review.
 * Also reads verified businesses' public profiles. Claims need sign-in; profiles don't.
 */
@Suppress("TooManyFunctions") // One function per claim step; splitting them would scatter one small API.
interface BusinessClaims {
    /** Starts a claim; the code goes to [ClaimForm.number]. */
    suspend fun start(form: ClaimForm): AppResult<BusinessClaim>

    /** The user's claims, newest first. */
    suspend fun claims(): AppResult<List<BusinessClaim>>

    /** Proves control of the number with [code]. */
    suspend fun verify(
        id: String,
        code: String,
    ): AppResult<BusinessClaim>

    /** Sends a new code by [method]. */
    suspend fun resend(
        id: String,
        method: ClaimMethod,
    ): AppResult<BusinessClaim>

    /** Uploads [file] as a [kind] of claim [id] and returns its key for [submit]. */
    suspend fun upload(
        id: String,
        kind: ClaimFileKind,
        file: ClaimFile,
    ): AppResult<String>

    /** Submits claim [id] with the uploaded [documentKeys] and optional [logoKey] for review. */
    suspend fun submit(
        id: String,
        documentKeys: List<String>,
        logoKey: String?,
    ): AppResult<BusinessClaim>

    /** Withdraws claim [id] (its files are deleted). */
    suspend fun withdraw(id: String): AppResult<Unit>

    /** Verified business [businessId]'s public profile. */
    suspend fun profile(businessId: String): AppResult<BusinessProfile>

    /** The logo at API path [path] (from [BusinessProfile.logoUrl]), or `null` if it isn't a small PNG or JPEG. */
    suspend fun logo(path: String): AppResult<ByteArray?>
}

/** [BusinessClaims] over the server API. */
@Singleton
class BusinessClaimRepository
    @Inject
    constructor(
        private val api: BusinessApi,
    ) : BusinessClaims {
        override suspend fun start(form: ClaimForm): AppResult<BusinessClaim> =
            apiCall {
                api.startClaim(
                    StartClaimDto(
                        e164 = form.number.e164,
                        legalName = form.legalName.trim(),
                        displayName = form.displayName.trim(),
                        category = form.category.wire,
                        website = form.website?.trim()?.ifEmpty { null },
                        hours = form.hours?.trim()?.ifEmpty { null },
                        callReason = form.callReason?.trim()?.ifEmpty { null },
                        method = form.method.wire,
                    ),
                )
            }.toClaim()

        override suspend fun claims(): AppResult<List<BusinessClaim>> =
            apiCall { api.claims() }.map { response -> response.claims.mapNotNull { it.toModel() } }

        override suspend fun verify(
            id: String,
            code: String,
        ): AppResult<BusinessClaim> = apiCall { api.verify(id, ClaimCodeDto(code)) }.toClaim()

        override suspend fun resend(
            id: String,
            method: ClaimMethod,
        ): AppResult<BusinessClaim> = apiCall { api.resend(id, ResendCodeDto(method.wire)) }.toClaim()

        override suspend fun upload(
            id: String,
            kind: ClaimFileKind,
            file: ClaimFile,
        ): AppResult<String> =
            apiCall { api.uploadUrl(id, ClaimUploadRequestDto(kind.wire, file.contentType)) }.flatMap { target ->
                if (file.bytes.size > target.maxBytes) {
                    AppResult.Failure(AppError.Validation(FILE_TOO_LARGE))
                } else {
                    val body = file.bytes.toRequestBody(target.contentType.toMediaType())
                    apiCallNoContent { api.upload(target.uploadUrl, target.contentType, body) }.map { target.key }
                }
            }

        override suspend fun submit(
            id: String,
            documentKeys: List<String>,
            logoKey: String?,
        ): AppResult<BusinessClaim> = apiCall { api.submit(id, SubmitClaimDto(documentKeys, logoKey)) }.toClaim()

        override suspend fun withdraw(id: String): AppResult<Unit> = apiCallNoContent { api.withdraw(id) }

        override suspend fun profile(businessId: String): AppResult<BusinessProfile> =
            apiCall { api.business(businessId) }.map { dto ->
                BusinessProfile(
                    id = dto.id,
                    name = dto.name,
                    category = BusinessCategory.parse(dto.category),
                    website = dto.website?.takeIf { it.startsWith("https://") },
                    hours = dto.hours,
                    logoUrl = dto.logoUrl,
                    numbers =
                        dto.numbers
                            .filter {
                                PhoneNumber.isE164(
                                    it.e164,
                                )
                            }.map { BusinessNumber(PhoneNumber(it.e164), it.callReason) },
                )
            }

        override suspend fun logo(path: String): AppResult<ByteArray?> =
            if (LOGO_PATH.matches(path)) {
                apiCall { api.logo(path.removePrefix("/")) }.readLimited(ClaimFileKind.MAX_LOGO_BYTES)
            } else {
                AppResult.Success(null)
            }

        private fun AppResult<ClaimDto>.toClaim(): AppResult<BusinessClaim> =
            flatMap { dto -> dto.toModel()?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Validation(UNKNOWN_CLAIM)) }

        /** Error codes. */
        companion object {
            /** The file is larger than the server accepts. */
            const val FILE_TOO_LARGE = "file_too_large"

            /** The server answered with a claim this app version can't read. */
            const val UNKNOWN_CLAIM = "unknown_claim"

            private val LOGO_PATH = Regex("/v1/business/[0-9a-f-]{36}/logo")
        }
    }

/** The claim, or `null` when its number or status isn't one this app version understands. */
internal fun ClaimDto.toModel(): BusinessClaim? {
    val status = ClaimStatus.parse(status)
    return if (status == null || !PhoneNumber.isE164(e164)) {
        null
    } else {
        BusinessClaim(
            id = id,
            number = PhoneNumber(e164),
            displayName = displayName,
            category = BusinessCategory.parse(category),
            method = ClaimMethod.parse(method),
            status = status,
            codeExpiresInSeconds = codeExpiresInSeconds,
            reviewNote = reviewNote,
            businessId = businessId,
            createdAtMillis = createdAt,
        )
    }
}
