package io.github.jeshiomurmu.opencaller.feature.business

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaims
import io.github.jeshiomurmu.opencaller.core.data.business.ClaimFile
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessClaim
import io.github.jeshiomurmu.opencaller.core.model.BusinessProfile
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind
import io.github.jeshiomurmu.opencaller.core.model.ClaimForm
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val BUSINESS = PhoneNumber("+8801812345678")
private const val BUSINESS_ID = "0b6a3c52-1f0e-4f7b-9d2a-5c1e8f3a7b10"

/** [BusinessClaims] that answers with what the test set and records what was sent. */
internal class FakeClaims : BusinessClaims {
    var failWith: AppError? = null
    val started = mutableListOf<ClaimForm>()
    val codes = mutableListOf<String>()
    val uploads = mutableListOf<Pair<ClaimFileKind, ClaimFile>>()
    var submitted: Pair<List<String>, String?>? = null
    val withdrawn = mutableListOf<String>()
    var list = emptyList<BusinessClaim>()
    var profile =
        BusinessProfile(BUSINESS_ID, "Example Bank", BusinessCategory.BANK, null, null, "/v1/business/$BUSINESS_ID/logo", emptyList())

    private fun <T> answer(value: () -> T): AppResult<T> = failWith?.let { AppResult.Failure(it) } ?: AppResult.Success(value())

    override suspend fun start(form: ClaimForm) =
        answer { started += form }.let { r -> r.mapClaim(claim(ClaimStatus.VERIFYING, form.method)) }

    override suspend fun claims() = answer { list }

    override suspend fun verify(
        id: String,
        code: String,
    ) = answer { codes += code }.mapClaim(claim(if (code == "123456") ClaimStatus.VERIFIED else ClaimStatus.VERIFYING))

    override suspend fun resend(
        id: String,
        method: ClaimMethod,
    ) = answer { }.mapClaim(claim(ClaimStatus.VERIFYING, method))

    override suspend fun upload(
        id: String,
        kind: ClaimFileKind,
        file: ClaimFile,
    ) = answer {
        uploads += kind to file
        "claims/$id/${kind.wire}-${uploads.size}"
    }

    override suspend fun submit(
        id: String,
        documentKeys: List<String>,
        logoKey: String?,
    ) = answer { submitted = documentKeys to logoKey }.mapClaim(claim(ClaimStatus.SUBMITTED))

    override suspend fun withdraw(id: String) = answer { withdrawn += id }

    override suspend fun profile(businessId: String) = answer { profile }

    override suspend fun logo(path: String): AppResult<ByteArray?> = answer { byteArrayOf(1, 2, 3) }

    private fun <T> AppResult<T>.mapClaim(claim: BusinessClaim): AppResult<BusinessClaim> =
        when (this) {
            is AppResult.Success -> AppResult.Success(claim)
            is AppResult.Failure -> this
        }

    fun claim(
        status: ClaimStatus,
        method: ClaimMethod = ClaimMethod.SMS,
    ) = BusinessClaim("c1", BUSINESS, "Example Bank", BusinessCategory.BANK, method, status, 300, null, null, 1)
}

/** Picked files that need no content resolver. */
private class FakeFiles : ClaimFileReader {
    var failWith: AppError? = null

    override suspend fun document(uri: String) =
        failWith?.let { AppResult.Failure(it) }
            ?: AppResult.Success(PickedFile(uri.substringAfterLast('/'), ClaimFile(ByteArray(10), "application/pdf")))

    override suspend fun logo(uri: String) = AppResult.Success(PickedFile("logo.png", ClaimFile(ByteArray(5), "image/png")))
}

private class FakeAccount(
    override val serverConfigured: Boolean = true,
) : AccountStatus {
    override val signedIn = MutableStateFlow(true)
    override val unlistPageUrl: String? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val claims = FakeClaims()
    private val files = FakeFiles()
    private val account = FakeAccount()

    private fun TestScope.claimVm(account: AccountStatus = this@BusinessViewModelsTest.account) =
        ClaimViewModel(claims, files, account, PhoneNumberNormalizer()) { "BD" }.also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    private fun ClaimViewModel.fillForm(number: String = "01812345678") {
        onEvent(ClaimEvent.NewClaim)
        onEvent(
            ClaimEvent.EditFields(
                ClaimFields(number, "Example Bank Ltd", "Example Bank", BusinessCategory.BANK, callReason = "Card security"),
            ),
        )
    }

    @Test
    fun tcBiz001AClaimGoesFromTheFormToTheCodeToTheDocumentsToReview() =
        runTest {
            val vm = claimVm()
            vm.fillForm()
            assertTrue(vm.uiState.value.canStart)
            vm.onEvent(ClaimEvent.Start)
            // The national number is sent in E.164, with the business details.
            assertEquals(BUSINESS, claims.started.single().number)
            assertEquals(ClaimStep.CODE, vm.uiState.value.step)

            vm.onEvent(ClaimEvent.EditCode("12-34-56 7"))
            assertEquals("123456", vm.uiState.value.code)
            vm.onEvent(ClaimEvent.Verify)
            assertEquals(ClaimStep.DOCUMENTS, vm.uiState.value.step)
            assertFalse("a document is required", vm.uiState.value.canSubmit)

            vm.onEvent(ClaimEvent.AddDocument("content://docs/registration.pdf"))
            vm.onEvent(ClaimEvent.SetLogo("content://media/logo"))
            assertEquals(
                listOf("registration.pdf"),
                vm.uiState.value.documents
                    .map { it.name },
            )
            assertEquals(listOf(ClaimFileKind.DOCUMENT, ClaimFileKind.LOGO), claims.uploads.map { it.first })
            vm.onEvent(ClaimEvent.Submit)

            assertEquals(listOf("claims/c1/document-1") to "claims/c1/logo-2", claims.submitted)
            val state = vm.uiState.value
            assertEquals(ClaimStep.LIST, state.step)
            assertEquals(ClaimStatus.SUBMITTED, state.claims.single().status)
        }

    @Test
    fun aWrongCodeStaysOnTheCodeStepAndANewCodeCanComeByPhoneCall() =
        runTest {
            val vm = claimVm()
            vm.fillForm()
            vm.onEvent(ClaimEvent.Start)
            vm.onEvent(ClaimEvent.EditCode("12345"))
            assertFalse("six digits are needed", vm.uiState.value.canVerify)
            claims.failWith = AppError.Validation("invalid_code")
            vm.onEvent(ClaimEvent.EditCode("654321"))
            vm.onEvent(ClaimEvent.Verify)
            assertEquals(BusinessProblem.WrongCode, vm.uiState.value.problem)
            assertEquals(ClaimStep.CODE, vm.uiState.value.step)

            claims.failWith = null
            vm.onEvent(ClaimEvent.Resend(ClaimMethod.VOICE))
            assertEquals(
                ClaimMethod.VOICE,
                vm.uiState.value.active
                    ?.method,
            )
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun theFormNeedsAValidNumberAndShowsTheServersAnswer() =
        runTest {
            val vm = claimVm()
            vm.fillForm(number = "12")
            vm.onEvent(ClaimEvent.Start)
            assertEquals(BusinessProblem.InvalidNumber, vm.uiState.value.problem)
            assertTrue(claims.started.isEmpty())

            vm.fillForm()
            claims.failWith = AppError.Validation("claim_open")
            vm.onEvent(ClaimEvent.Start)
            assertEquals(BusinessProblem.ClaimOpen, vm.uiState.value.problem)
            assertEquals(ClaimStep.FORM, vm.uiState.value.step)
        }

    @Test
    fun aTooLargeDocumentIsRefusedBeforeUpload() =
        runTest {
            claims.list = listOf(claims.claim(ClaimStatus.VERIFIED))
            val vm = claimVm()
            vm.onEvent(ClaimEvent.Refresh)
            vm.onEvent(
                ClaimEvent.Continue(
                    vm.uiState.value.claims
                        .single(),
                ),
            )
            assertEquals(ClaimStep.DOCUMENTS, vm.uiState.value.step)
            files.failWith = AppError.Validation("file_too_large")
            vm.onEvent(ClaimEvent.AddDocument("content://docs/huge.pdf"))
            assertEquals(BusinessProblem.FileTooLarge, vm.uiState.value.problem)
            assertTrue(claims.uploads.isEmpty())
        }

    @Test
    fun claimsCanBeWithdrawnAndContinued() =
        runTest {
            claims.list = listOf(claims.claim(ClaimStatus.VERIFYING))
            val vm = claimVm()
            vm.onEvent(ClaimEvent.Refresh)
            vm.onEvent(
                ClaimEvent.Continue(
                    vm.uiState.value.claims
                        .single(),
                ),
            )
            assertEquals(ClaimStep.CODE, vm.uiState.value.step)
            vm.onEvent(ClaimEvent.Back)
            vm.onEvent(ClaimEvent.Withdraw("c1"))
            assertEquals(listOf("c1"), claims.withdrawn)
            assertEquals(
                ClaimStatus.WITHDRAWN,
                vm.uiState.value.claims
                    .single()
                    .status,
            )
        }

    @Test
    fun withoutAServerNothingIsAsked() =
        runTest {
            val vm = claimVm(FakeAccount(serverConfigured = false))
            assertFalse(vm.uiState.value.serverConfigured)
        }

    @Test
    fun aBusinessProfileLoadsWithItsLogo() =
        runTest {
            val vm = BusinessProfileViewModel(claims)
            vm.open(BUSINESS_ID)
            val state = vm.uiState.value
            assertEquals("Example Bank", state.profile?.name)
            assertArrayEquals(byteArrayOf(1, 2, 3), state.logo?.bytes)

            val other = BusinessProfileViewModel(claims)
            other.open("verified")
            assertEquals(BusinessProblem.NotFound, other.uiState.value.problem)
            claims.failWith = AppError.NotFound
            val gone = BusinessProfileViewModel(claims)
            gone.open(BUSINESS_ID)
            assertEquals(BusinessProblem.NotFound, gone.uiState.value.problem)
        }
}
