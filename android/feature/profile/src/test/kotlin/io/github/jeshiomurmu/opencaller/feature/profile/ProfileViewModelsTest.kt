package io.github.jeshiomurmu.opencaller.feature.profile

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.profile.ProfileEdit
import io.github.jeshiomurmu.opencaller.core.data.profile.Profiles
import io.github.jeshiomurmu.opencaller.core.data.profile.SUPPORTER_REQUIRED
import io.github.jeshiomurmu.opencaller.core.model.ContactRequest
import io.github.jeshiomurmu.opencaller.core.model.MyProfile
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisibility
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisit
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.PublicProfile
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val ME = PhoneNumber("+8801712345678")
private val OTHER = PhoneNumber("+12015550123")

/** [Profiles] that answers with whatever the test set and records what was sent. */
class FakeProfiles : Profiles {
    var profile =
        MyProfile(ME, "Asha", "avatars/u/a.jpg", "http://pictures/a", ProfileVisibility.REGISTERED_ONLY, false, null, false, 2, 3, 1)
    var failWith: AppError? = null
    var visitsResult: AppResult<ProfileVisits> = AppResult.Success(ProfileVisits(VisitKind.VIEW, 90, 0, emptyList()))
    var requests = mutableListOf(ContactRequest(7, OTHER, "Rafi", 1_000))
    val updates = mutableListOf<ProfileEdit>()
    val uploads = mutableListOf<ByteArray>()
    val decisions = mutableListOf<Pair<Long, Boolean>>()
    val asked = mutableListOf<PhoneNumber>()
    var deleted = false

    private fun <T> answer(value: () -> T): AppResult<T> = failWith?.let { AppResult.Failure(it) } ?: AppResult.Success(value())

    override suspend fun me() = answer { profile }

    override suspend fun update(edit: ProfileEdit) =
        answer {
            updates += edit
            profile
                .copy(
                    displayName = edit.displayName,
                    visibility = edit.visibility,
                    incognito = edit.incognito,
                    avatarKey = edit.avatarKey,
                ).also { profile = it }
        }

    override suspend fun uploadAvatar(jpeg: ByteArray) = answer { "avatars/u/new.jpg".also { uploads += jpeg } }

    override suspend fun picture(url: String) = answer { byteArrayOf(1, 2, 3) }

    override suspend fun visits(kind: VisitKind) = visitsResult

    override suspend fun profile(number: PhoneNumber) = answer { PublicProfile(number, null, null, null) }

    override suspend fun requestContact(number: PhoneNumber) = answer { asked += number }

    override suspend fun contactRequests() = answer { requests.toList() }

    override suspend fun decide(
        id: Long,
        approve: Boolean,
    ) = answer { decisions += id to approve }

    override suspend fun deleteAccount() = answer { deleted = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val scaled = byteArrayOf(9, 9)
    private val myProfile by lazy { MyProfileViewModel(profiles) { AppResult.Success(scaled) } }

    @Test
    fun theProfileLoadsWithItsPictureAndCounts() =
        runTest {
            myProfile.onEvent(MyProfileEvent.Load)
            val state = myProfile.uiState.value
            assertFalse(state.loading)
            assertEquals("Asha", state.name)
            assertEquals(ProfileVisibility.REGISTERED_ONLY, state.visibility)
            assertArrayEquals(byteArrayOf(1, 2, 3), state.picture?.bytes)
            assertEquals(2, state.viewerCount)
            assertEquals(3, state.searcherCount)
            assertEquals(1, state.pendingRequests)
            assertFalse(state.dirty)
        }

    @Test
    fun editsAreSavedTogetherAndNamesStayOnOneShortLine() =
        runTest {
            myProfile.onEvent(MyProfileEvent.Load)
            myProfile.onEvent(MyProfileEvent.NameChanged("Asha\nRahman" + "x".repeat(80)))
            assertEquals(ProfileLimits.MAX_NAME, myProfile.uiState.value.name.length)
            assertFalse('\n' in myProfile.uiState.value.name)
            myProfile.onEvent(MyProfileEvent.NameChanged("  Asha R  "))
            myProfile.onEvent(MyProfileEvent.VisibilityChanged(ProfileVisibility.NOBODY))
            myProfile.onEvent(MyProfileEvent.IncognitoChanged(true))
            assertTrue(myProfile.uiState.value.dirty)
            myProfile.onEvent(MyProfileEvent.Save)
            assertEquals(ProfileEdit("Asha R", ProfileVisibility.NOBODY, true, "avatars/u/a.jpg"), profiles.updates.single())
            assertTrue(myProfile.uiState.value.saved)
            assertFalse(myProfile.uiState.value.dirty)
            // An empty name clears it.
            myProfile.onEvent(MyProfileEvent.NameChanged(" "))
            myProfile.onEvent(MyProfileEvent.Save)
            assertNull(profiles.updates.last().displayName)
        }

    @Test
    fun aPickedPictureIsUploadedAtOnceAndSavedWithTheProfile() =
        runTest {
            myProfile.onEvent(MyProfileEvent.Load)
            myProfile.onEvent(MyProfileEvent.PicturePicked("content://media/picker/1"))
            assertArrayEquals(scaled, profiles.uploads.single())
            assertEquals("avatars/u/new.jpg", myProfile.uiState.value.avatarKey)
            assertArrayEquals(
                scaled,
                myProfile.uiState.value.picture
                    ?.bytes,
            )
            assertTrue(myProfile.uiState.value.dirty)
            myProfile.onEvent(MyProfileEvent.Save)
            assertEquals("avatars/u/new.jpg", profiles.updates.single().avatarKey)
            myProfile.onEvent(MyProfileEvent.RemovePicture)
            assertNull(myProfile.uiState.value.picture)
            myProfile.onEvent(MyProfileEvent.Save)
            assertNull(profiles.updates.last().avatarKey)
        }

    @Test
    fun aPictureThatCantBeReadIsReported() =
        runTest {
            val vm = MyProfileViewModel(profiles) { AppResult.Failure(AppError.Storage) }
            vm.onEvent(MyProfileEvent.Load)
            vm.onEvent(MyProfileEvent.PicturePicked("content://broken"))
            assertEquals(ProfileProblem.PICTURE, vm.uiState.value.problem)
            assertTrue(profiles.uploads.isEmpty())
            assertEquals("avatars/u/a.jpg", vm.uiState.value.avatarKey)
        }

    // F-SO-07: deleting asks twice and only the third tap deletes.
    @Test
    fun deletingTheAccountAsksTwice() =
        runTest {
            myProfile.onEvent(MyProfileEvent.Load)
            myProfile.onEvent(MyProfileEvent.Delete)
            assertEquals(DeleteStep.FIRST_CONFIRM, myProfile.uiState.value.deleteStep)
            myProfile.onEvent(MyProfileEvent.CancelDelete)
            assertEquals(DeleteStep.NONE, myProfile.uiState.value.deleteStep)
            myProfile.onEvent(MyProfileEvent.Delete)
            myProfile.onEvent(MyProfileEvent.Delete)
            assertEquals(DeleteStep.SECOND_CONFIRM, myProfile.uiState.value.deleteStep)
            assertFalse(profiles.deleted)
            myProfile.onEvent(MyProfileEvent.Delete)
            assertTrue(profiles.deleted)
            assertTrue(myProfile.uiState.value.deleted)
        }

    @Test
    fun loadProblemsAreNamed() =
        runTest {
            profiles.failWith = AppError.Network
            myProfile.onEvent(MyProfileEvent.Load)
            assertEquals(ProfileProblem.OFFLINE, myProfile.uiState.value.problem)
            profiles.failWith = AppError.Auth
            myProfile.onEvent(MyProfileEvent.Load)
            assertEquals(ProfileProblem.SIGNED_OUT, myProfile.uiState.value.problem)
        }

    @Test
    fun visitorListsAreForSupporters() =
        runTest {
            val vm = VisitorsViewModel(profiles)
            profiles.visitsResult = AppResult.Failure(AppError.Validation(SUPPORTER_REQUIRED))
            vm.open(VisitKind.SEARCH)
            assertTrue(vm.uiState.value.supporterRequired)
            assertEquals(VisitKind.SEARCH, vm.uiState.value.kind)
            val visits =
                ProfileVisits(VisitKind.VIEW, 90, 5, listOf(ProfileVisit("Vera", SupporterBadge.GOLD, 1), ProfileVisit(null, null, 2)))
            profiles.visitsResult = AppResult.Success(visits)
            vm.open(VisitKind.VIEW)
            assertFalse(vm.uiState.value.supporterRequired)
            assertEquals(visits, vm.uiState.value.visits)
        }

    @Test
    fun answeredContactRequestsLeaveTheList() =
        runTest {
            val vm = ContactRequestsViewModel(profiles)
            vm.load()
            assertEquals(
                listOf(7L),
                vm.uiState.value.requests
                    .map { it.id },
            )
            vm.answer(7, approve = true)
            assertEquals(listOf(7L to true), profiles.decisions)
            assertTrue(
                vm.uiState.value.requests
                    .isEmpty(),
            )
        }

    @Test
    fun askingForAHiddenNameSendsOneRequest() =
        runTest {
            val vm = PublicProfileViewModel(profiles)
            vm.open(OTHER.e164)
            assertNull(
                vm.uiState.value.profile
                    ?.name,
            )
            vm.askForName()
            vm.askForName()
            assertEquals(listOf(OTHER), profiles.asked)
            assertTrue(vm.uiState.value.requestSent)
            vm.open("not a number")
            assertEquals(ProfileProblem.SERVER, vm.uiState.value.problem)
        }
}
