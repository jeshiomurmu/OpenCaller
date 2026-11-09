package io.github.jeshiomurmu.opencaller.feature.family

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyGroups
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyMembership
import io.github.jeshiomurmu.opencaller.core.model.FamilyGroup
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvite
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [FamilyGroups] answering with what the test set, recording what was sent. */
class FakeFamilyGroups : FamilyGroups {
    var group: FamilyGroup? = null
    var failWith: AppError? = null
    val joins = mutableListOf<Triple<String, FamilySharing, Boolean>>()
    val updates = mutableListOf<FamilySharing>()
    val removed = mutableListOf<String>()
    var left = false

    private fun <T> answer(value: () -> T): AppResult<T> = failWith?.let { AppResult.Failure(it) } ?: AppResult.Success(value())

    override val membership: Flow<FamilyMembership> = flowOf(FamilyMembership.NONE)

    override suspend fun refresh() = answer { group }

    override suspend fun create(
        name: String,
        label: String,
    ) = answer { group(name, label, FamilyRole.OWNER, FamilySharing.NONE).also { group = it } }

    override suspend fun invite() = answer { FamilyInvite("ABCD2345", 1_000L) }

    override suspend fun preview(code: String) =
        answer { FamilyInvitePreview(code, "Rahman family", "Rafi", 1, FamilySharing.MAX_MEMBERS, 1_000L, FamilySharing.CONSENT_VERSION) }

    override suspend fun join(
        invite: FamilyInvitePreview,
        label: String,
        sharing: FamilySharing,
        consented: Boolean,
    ) = answer {
        joins += Triple(label, sharing, consented)
        group(invite.groupName, label, FamilyRole.MEMBER, sharing).also { group = it }
    }

    override suspend fun update(
        label: String,
        sharing: FamilySharing,
    ) = answer {
        updates += sharing
        checkNotNull(group).copy(mySharing = sharing).also { group = it }
    }

    override suspend fun leave() =
        answer {
            left = true
            group = null
        }

    override suspend fun remove(memberId: String) =
        answer {
            removed += memberId
            group
        }

    private fun group(
        name: String,
        label: String,
        role: FamilyRole,
        sharing: FamilySharing,
    ) = FamilyGroup("g1", name, "Rafi", 2, FamilySharing.MAX_MEMBERS, label, role, sharing, 0L, emptyList(), emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val signedIn = MutableStateFlow(true)
    private val account =
        object : AccountStatus {
            override val signedIn: Flow<Boolean> = this@FamilyViewModelTest.signedIn
            override val serverConfigured: Boolean = true
            override val unlistPageUrl: String? = null
        }
    private val family = FakeFamilyGroups()

    @Test
    fun signedOutShowsSignInAndAskNothingOfTheServer() =
        runTest {
            signedIn.value = false
            val viewModel = FamilyViewModel(account, family)
            assertFalse(viewModel.uiState.value.signedIn)
            assertFalse(viewModel.uiState.value.loading)
        }

    @Test
    fun tcFam001JoiningGoesThroughTheConsentScreenWithTheMembersChoices() =
        runTest {
            val viewModel = FamilyViewModel(account, family)
            assertNull(viewModel.uiState.value.group)
            assertFalse(viewModel.uiState.value.loading)

            viewModel.onEvent(FamilyEvent.OpenInvite("ABCD2345"))
            assertEquals(
                "Rafi",
                viewModel.uiState.value.preview
                    ?.adminLabel,
            )
            viewModel.onEvent(FamilyEvent.CancelJoin)
            assertNull(viewModel.uiState.value.preview)
            assertTrue(family.joins.isEmpty())

            viewModel.onEvent(FamilyEvent.OpenInvite("ABCD2345"))
            val sharing = FamilySharing(otpAlerts = true, dailyCounts = false)
            viewModel.onEvent(FamilyEvent.Join("Mum", sharing, consented = true))
            assertEquals(Triple("Mum", sharing, true), family.joins.single())
            val state = viewModel.uiState.value
            assertNull(state.preview)
            assertEquals(FamilyRole.MEMBER, state.group?.myRole)

            // The member switches daily counts on later.
            viewModel.onEvent(FamilyEvent.SetSharing(sharing.copy(dailyCounts = true)))
            assertEquals(FamilySharing(otpAlerts = true, dailyCounts = true), family.updates.single())
            assertEquals(
                true,
                viewModel.uiState.value.group
                    ?.mySharing
                    ?.dailyCounts,
            )
        }

    @Test
    fun theAdminInvitesRemovesAndEndsTheGroup() =
        runTest {
            val viewModel = FamilyViewModel(account, family)
            viewModel.onEvent(FamilyEvent.Create("Rahman family", "Rafi"))
            assertTrue(
                viewModel.uiState.value.group
                    ?.isAdmin == true,
            )
            viewModel.onEvent(FamilyEvent.Invite)
            assertEquals(
                "ABCD2345",
                viewModel.uiState.value.invite
                    ?.code,
            )
            viewModel.onEvent(FamilyEvent.InviteShown)
            assertNull(viewModel.uiState.value.invite)
            viewModel.onEvent(FamilyEvent.Remove("m1"))
            assertEquals(listOf("m1"), family.removed)
            viewModel.onEvent(FamilyEvent.Leave)
            assertTrue(family.left)
            assertNull(viewModel.uiState.value.group)
        }

    @Test
    fun failuresShowAPlainMessage() =
        runTest {
            val viewModel = FamilyViewModel(account, family)
            family.failWith = AppError.NotFound
            viewModel.onEvent(FamilyEvent.OpenInvite("ZZZZ2222"))
            assertEquals(R.string.family_error_code, viewModel.uiState.value.error)
            family.failWith = AppError.Validation("already_in_family")
            viewModel.onEvent(FamilyEvent.Create("x", "y"))
            assertEquals(R.string.family_error_already_in, viewModel.uiState.value.error)
            family.failWith = null
            viewModel.onEvent(FamilyEvent.Refresh)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun codesAreShownInTwoHalvesAndLabelsStayOnOneShortLine() {
        assertEquals("ABCD-2345", displayCode("ABCD2345"))
        assertEquals("Mum Rahman", oneLine("Mum\nRahman"))
        assertEquals(FamilySharing.MAX_LABEL, oneLine("x".repeat(80)).length)
    }
}
