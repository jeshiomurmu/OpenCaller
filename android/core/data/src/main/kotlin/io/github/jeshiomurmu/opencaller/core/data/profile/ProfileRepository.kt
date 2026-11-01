package io.github.jeshiomurmu.opencaller.core.data.profile

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.account.KeystoreTokenStore
import io.github.jeshiomurmu.opencaller.core.model.ContactRequest
import io.github.jeshiomurmu.opencaller.core.model.MyProfile
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.PublicProfile
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.core.network.ContactRequestDto
import io.github.jeshiomurmu.opencaller.core.network.ProfileApi
import io.github.jeshiomurmu.opencaller.core.network.UpdateMeDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [Profiles] over the server API (docs/architecture.md §8, Phase 8). */
@Singleton
class ProfileRepository
    @Inject
    constructor(
        private val api: ProfileApi,
        private val tokens: KeystoreTokenStore,
        private val dispatchers: DispatcherProvider,
    ) : Profiles {
        override suspend fun me(): AppResult<MyProfile> = apiCall { api.me() }.map { it.toProfile() }

        override suspend fun update(edit: ProfileEdit): AppResult<MyProfile> =
            apiCall {
                api.updateMe(
                    UpdateMeDto(
                        displayName = edit.displayName?.trim()?.ifEmpty { null },
                        visibility = edit.visibility.name.lowercase(),
                        incognito = edit.incognito,
                        avatarKey = edit.avatarKey,
                    ),
                )
            }.map { it.toProfile() }

        override suspend fun uploadAvatar(jpeg: ByteArray): AppResult<String> =
            apiCall { api.avatarUpload() }.flatMap { target ->
                if (jpeg.size > target.maxBytes) {
                    AppResult.Failure(AppError.Validation(PICTURE_TOO_LARGE))
                } else {
                    val body = jpeg.toRequestBody(target.contentType.toMediaType())
                    apiCallNoContent { api.upload(target.uploadUrl, target.contentType, body) }.map { target.avatarKey }
                }
            }

        override suspend fun picture(url: String): AppResult<ByteArray> = apiCall { api.picture(url) }.readBytes(dispatchers.io)

        override suspend fun visits(kind: VisitKind): AppResult<ProfileVisits> =
            apiCall { if (kind == VisitKind.VIEW) api.viewers() else api.searchers() }.map { it.toVisits(kind) }

        override suspend fun profile(number: PhoneNumber): AppResult<PublicProfile> =
            apiCall { api.profile(number.e164) }.map {
                PublicProfile(number, it.name, it.avatarUrl, SupporterBadge.parse(it.badge).takeIf { _ -> it.name != null })
            }

        override suspend fun requestContact(number: PhoneNumber): AppResult<Unit> =
            apiCall { api.requestContact(ContactRequestDto(number.e164)) }.map { }

        override suspend fun contactRequests(): AppResult<List<ContactRequest>> =
            apiCall { api.contactRequests() }.map { response ->
                response.items
                    .filter { PhoneNumber.isE164(it.requesterE164) }
                    .map { ContactRequest(it.id, PhoneNumber(it.requesterE164), it.requesterName, millisOf(it.createdAt)) }
            }

        override suspend fun decide(
            id: Long,
            approve: Boolean,
        ): AppResult<Unit> = apiCall { api.decide(id, if (approve) "approve" else "deny") }.map { }

        override suspend fun deleteAccount(): AppResult<Unit> = apiCallNoContent { api.deleteMe() }.map { tokens.clear() }

        /** Error codes. */
        companion object {
            /** The picture is larger than the server accepts. */
            const val PICTURE_TOO_LARGE = "picture_too_large"
        }
    }

// Pictures are small (the server refuses more than 1 MB), so reading one into memory is fine.
private suspend fun AppResult<ResponseBody>.readBytes(io: CoroutineDispatcher): AppResult<ByteArray> =
    when (this) {
        is AppResult.Success -> {
            try {
                AppResult.Success(withContext(io) { value.use { it.bytes() } })
            } catch (e: IOException) {
                Timber.w(e, "Picture download failed")
                AppResult.Failure(AppError.Network)
            }
        }

        is AppResult.Failure -> {
            this
        }
    }
