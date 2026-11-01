package io.github.jeshiomurmu.opencaller.core.data.profile

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.ContactRequest
import io.github.jeshiomurmu.opencaller.core.model.MyProfile
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisibility
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.PublicProfile
import io.github.jeshiomurmu.opencaller.core.model.VisitKind

/** Problem code of the supporter-only lists (`AppError.Validation`). */
const val SUPPORTER_REQUIRED = "supporter_required"

/** The editable part of the own profile; [avatarKey] `null` removes the picture. */
data class ProfileEdit(
    val displayName: String?,
    val visibility: ProfileVisibility,
    val incognito: Boolean,
    val avatarKey: String?,
)

/**
 * The signed-in user's profile and its social side (docs/prd.md §6.4): name, picture, visibility, incognito,
 * who viewed or searched (supporters), contact requests and account deletion. Everything needs sign-in.
 */
@Suppress("TooManyFunctions") // One function per profile action; splitting them would scatter one small API.
interface Profiles {
    /** The own profile. */
    suspend fun me(): AppResult<MyProfile>

    /** Replaces the editable profile with [edit]. */
    suspend fun update(edit: ProfileEdit): AppResult<MyProfile>

    /** Uploads [jpeg] as a new picture and returns its key, to save with [update]. */
    suspend fun uploadAvatar(jpeg: ByteArray): AppResult<String>

    /** The picture behind a presigned [url] (from [MyProfile.avatarUrl] or a profile), as JPEG bytes. */
    suspend fun picture(url: String): AppResult<ByteArray>

    /** Who viewed the profile or searched for the number; `AppError.Validation("supporter_required")` for others. */
    suspend fun visits(kind: VisitKind): AppResult<ProfileVisits>

    /** Another number's profile as this user may see it (recorded as a view unless incognito). */
    suspend fun profile(number: PhoneNumber): AppResult<PublicProfile>

    /** Asks the owner of [number] for their name; the answer never says whether anyone received it. */
    suspend fun requestContact(number: PhoneNumber): AppResult<Unit>

    /** Pending requests for the user's name. */
    suspend fun contactRequests(): AppResult<List<ContactRequest>>

    /** Approves ([approve]) or denies request [id]. */
    suspend fun decide(
        id: Long,
        approve: Boolean,
    ): AppResult<Unit>

    /** Deletes the account on the server, then signs out on this phone. */
    suspend fun deleteAccount(): AppResult<Unit>
}
