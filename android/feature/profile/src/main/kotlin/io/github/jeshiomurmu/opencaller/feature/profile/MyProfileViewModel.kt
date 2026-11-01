package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.profile.ProfileEdit
import io.github.jeshiomurmu.opencaller.core.data.profile.Profiles
import io.github.jeshiomurmu.opencaller.core.model.MyProfile
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisibility
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** JPEG bytes of a picture (identity equality: a new picture is a new object). */
class PictureBytes(
    val bytes: ByteArray,
)

/** Where the two-step account deletion stands (F-SO-07: deleting asks twice). */
enum class DeleteStep {
    NONE,
    FIRST_CONFIRM,
    SECOND_CONFIRM,
    DELETING,
}

/** Why the profile screen can't do what was asked. */
enum class ProfileProblem {
    OFFLINE,
    SIGNED_OUT,
    NOT_CONNECTED,
    PICTURE,
    SERVER,
}

/**
 * My profile (docs/prd.md F-SO-01/04/07).
 *
 * @property picture the picture to show: the saved one once downloaded, or a newly picked one not saved yet.
 * @property dirty true when the fields differ from the saved profile.
 */
data class MyProfileUiState(
    val loading: Boolean = true,
    val problem: ProfileProblem? = null,
    val number: PhoneNumber? = null,
    val name: String = "",
    val visibility: ProfileVisibility = ProfileVisibility.REGISTERED_ONLY,
    val incognito: Boolean = false,
    val avatarKey: String? = null,
    val picture: PictureBytes? = null,
    val badge: SupporterBadge? = null,
    val supporter: Boolean = false,
    val viewerCount: Int = 0,
    val searcherCount: Int = 0,
    val pendingRequests: Int = 0,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val deleteStep: DeleteStep = DeleteStep.NONE,
    val deleted: Boolean = false,
)

/** What the profile screen does. */
sealed interface MyProfileEvent {
    data object Load : MyProfileEvent

    data class NameChanged(
        val name: String,
    ) : MyProfileEvent

    data class VisibilityChanged(
        val visibility: ProfileVisibility,
    ) : MyProfileEvent

    data class IncognitoChanged(
        val incognito: Boolean,
    ) : MyProfileEvent

    /** A picture from the photo picker (its content URI). */
    data class PicturePicked(
        val uri: String,
    ) : MyProfileEvent

    data object RemovePicture : MyProfileEvent

    data object Save : MyProfileEvent

    /** Delete account: the first tap asks, the second asks again, the third deletes. */
    data object Delete : MyProfileEvent

    data object CancelDelete : MyProfileEvent
}

/** Turns a picked picture into a small JPEG for the profile. */
fun interface PictureScaler {
    /** The picture at [uri] as a JPEG of at most [ProfileLimits.PICTURE_SIDE] px on its longer side. */
    suspend fun scale(uri: String): AppResult<ByteArray>
}

/** Limits the editor applies before the server does. */
object ProfileLimits {
    /** Longest display name (the server's limit). */
    const val MAX_NAME = 60

    /** Longer side of a profile picture in pixels. */
    const val PICTURE_SIDE = 512
}

/** The profile editor: loads, edits, saves; uploads a picked picture at once and keeps its key until Save. */
@HiltViewModel
class MyProfileViewModel
    @Inject
    constructor(
        private val profiles: Profiles,
        private val scaler: PictureScaler,
    ) : ViewModel() {
        private val state = MutableStateFlow(MyProfileUiState())
        private var saved: MyProfile? = null

        /** Screen state. */
        val uiState: StateFlow<MyProfileUiState> = state.asStateFlow()

        /** Handles [event]. */
        fun onEvent(event: MyProfileEvent) {
            when (event) {
                MyProfileEvent.Load -> load()
                is MyProfileEvent.NameChanged -> edit { it.copy(name = event.name.replace('\n', ' ').take(ProfileLimits.MAX_NAME)) }
                is MyProfileEvent.VisibilityChanged -> edit { it.copy(visibility = event.visibility) }
                is MyProfileEvent.IncognitoChanged -> edit { it.copy(incognito = event.incognito) }
                is MyProfileEvent.PicturePicked -> pick(event.uri)
                MyProfileEvent.RemovePicture -> edit { it.copy(avatarKey = null, picture = null) }
                MyProfileEvent.Save -> save()
                MyProfileEvent.Delete -> delete()
                MyProfileEvent.CancelDelete -> state.update { it.copy(deleteStep = DeleteStep.NONE) }
            }
        }

        private fun load() {
            state.update { it.copy(loading = true, problem = null) }
            viewModelScope.launch {
                when (val result = profiles.me()) {
                    is AppResult.Success -> show(result.value)
                    is AppResult.Failure -> state.update { it.copy(loading = false, problem = result.error.toProblem()) }
                }
            }
        }

        private suspend fun show(profile: MyProfile) {
            saved = profile
            state.update {
                it.copy(
                    loading = false,
                    problem = null,
                    number = profile.number,
                    name = profile.displayName.orEmpty(),
                    visibility = profile.visibility,
                    incognito = profile.incognito,
                    avatarKey = profile.avatarKey,
                    picture = null,
                    badge = profile.badge,
                    supporter = profile.supporter,
                    viewerCount = profile.viewerCount,
                    searcherCount = profile.searcherCount,
                    pendingRequests = profile.pendingContactRequests,
                    dirty = false,
                    busy = false,
                )
            }
            val url = profile.avatarUrl ?: return
            (profiles.picture(url) as? AppResult.Success)?.let { bytes ->
                state.update { if (it.avatarKey == profile.avatarKey) it.copy(picture = PictureBytes(bytes.value)) else it }
            }
        }

        private fun edit(change: (MyProfileUiState) -> MyProfileUiState) {
            state.update { current ->
                change(current).let { it.copy(dirty = it.differsFrom(saved), saved = false) }
            }
        }

        private fun pick(uri: String) {
            state.update { it.copy(busy = true, problem = null) }
            viewModelScope.launch {
                val uploaded =
                    when (val scaled = scaler.scale(uri)) {
                        is AppResult.Success -> profiles.uploadAvatar(scaled.value).map { key -> key to scaled.value }
                        is AppResult.Failure -> AppResult.Failure(scaled.error)
                    }
                when (uploaded) {
                    is AppResult.Success -> {
                        state.update { it.copy(busy = false) }
                        edit { it.copy(avatarKey = uploaded.value.first, picture = PictureBytes(uploaded.value.second)) }
                    }

                    is AppResult.Failure -> {
                        state.update { it.copy(busy = false, problem = ProfileProblem.PICTURE) }
                    }
                }
            }
        }

        private fun save() {
            val current = state.value
            if (current.busy || current.number == null) return
            state.update { it.copy(busy = true, problem = null) }
            viewModelScope.launch {
                val edit = ProfileEdit(current.name.trim().ifEmpty { null }, current.visibility, current.incognito, current.avatarKey)
                when (val result = profiles.update(edit)) {
                    is AppResult.Success -> {
                        val picture = state.value.picture
                        show(result.value)
                        state.update { it.copy(saved = true, picture = picture ?: it.picture) }
                    }

                    is AppResult.Failure -> {
                        state.update { it.copy(busy = false, problem = result.error.toProblem()) }
                    }
                }
            }
        }

        private fun delete() {
            when (state.value.deleteStep) {
                DeleteStep.NONE -> state.update { it.copy(deleteStep = DeleteStep.FIRST_CONFIRM) }
                DeleteStep.FIRST_CONFIRM -> state.update { it.copy(deleteStep = DeleteStep.SECOND_CONFIRM) }
                DeleteStep.SECOND_CONFIRM -> reallyDelete()
                DeleteStep.DELETING -> Unit
            }
        }

        private fun reallyDelete() {
            state.update { it.copy(deleteStep = DeleteStep.DELETING, problem = null) }
            viewModelScope.launch {
                when (val result = profiles.deleteAccount()) {
                    is AppResult.Success -> {
                        state.update { it.copy(deleteStep = DeleteStep.NONE, deleted = true) }
                    }

                    is AppResult.Failure -> {
                        state.update { it.copy(deleteStep = DeleteStep.NONE, problem = result.error.toProblem()) }
                    }
                }
            }
        }
    }

private fun MyProfileUiState.differsFrom(profile: MyProfile?): Boolean =
    profile == null ||
        name.trim() != profile.displayName.orEmpty() ||
        visibility != profile.visibility ||
        incognito != profile.incognito ||
        avatarKey != profile.avatarKey

/** The [ProfileProblem] to show for [this] error. */
fun AppError.toProblem(): ProfileProblem =
    when (this) {
        AppError.Network -> ProfileProblem.OFFLINE
        AppError.Auth -> ProfileProblem.SIGNED_OUT
        AppError.ServerNotConfigured -> ProfileProblem.NOT_CONNECTED
        else -> ProfileProblem.SERVER
    }
