package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaimRepository
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessDirectoryRepository
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessLogoStore
import io.github.jeshiomurmu.opencaller.core.data.business.ClaimFile
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncStateStore
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind
import io.github.jeshiomurmu.opencaller.core.model.ClaimForm
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The verified-business directory and business claims against a local server (TC-BIZ-001 app side, F-BZ-02). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class BusinessDirectoryTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val tokens = MemoryTokens(AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE))
    private val network = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, FakeClock())
    private val stateStore = RegistrySyncStateStore(tempPreferences("registry"))
    private val logoDir: File = Files.createTempDirectory("logos").toFile()
    private val logos = BusinessLogoStore(logoDir, RealDispatchers)
    private var country = "BD"
    private val directory =
        BusinessDirectoryRepository(network.businessApi, DatabaseAccess { db }, RealDispatchers, stateStore, { country }, logos)
    private val claims = BusinessClaimRepository(network.businessApi)
    private val requested = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var directoryBody = directoryJson(entry("+8801812345678", withLogo = true), syncedAt = 1_000)
    private var logoBytes = PNG

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requested += request
                    val path = request.url.encodedPath
                    return when {
                        path.startsWith("/v1/businesses/") -> jsonResponse(200, directoryBody)
                        path == "/v1/business/$BUSINESS/logo" -> imageResponse(logoBytes)
                        path == "/v1/business/claims" && request.method == "POST" -> jsonResponse(201, claim("verifying"))
                        path == "/v1/business/claims/c1/uploads" -> jsonResponse(200, uploadTarget())
                        path == "/upload/doc" -> MockResponse.Builder().code(200).build()
                        else -> jsonResponse(404, """{"code":"not_found"}""")
                    }
                }
            }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun verifiedBusinessesAndTheirLogosAreKeptOnThePhone() =
        runBlocking {
            assertEquals(AppResult.Success(1), directory.sync())

            val business = directory.find(PhoneNumber("+8801812345678")).getOrThrow()
            assertNotNull(business)
            assertEquals("Example Bank", business!!.name)
            assertEquals(BusinessCategory.BANK, business.category)
            assertEquals("Card security", business.callReason)
            assertArrayEquals(PNG, File(business.logoPath!!).readBytes())
            assertNull(directory.find(PhoneNumber("+8801812345679")).getOrThrow())
            // Only the country is sent.
            assertEquals("/v1/businesses/BD", requested.first().url.encodedPath)
            assertEquals("BD", stateStore.state.first().businessCountry)
        }

    @Test
    fun revokedBusinessesAreRemovedAndTheNextUpdateAsksForNewerRows() =
        runBlocking {
            directory.sync()
            directoryBody = directoryJson(entry("+8801812345678", removed = true), syncedAt = 2_000)
            directory.sync()

            assertTrue(requested.any { it.url.encodedQuery == "since=1000" })
            assertNull(directory.find(PhoneNumber("+8801812345678")).getOrThrow())
            assertTrue("logos of removed businesses are deleted", logoDir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun aLogoThatIsNotASmallImageIsNotStoredAndAnotherCountryStartsOver() =
        runBlocking {
            logoBytes = "<html>".toByteArray()
            directory.sync()
            assertNull(directory.find(PhoneNumber("+8801812345678")).getOrThrow()!!.logoPath)

            country = "IN"
            directoryBody = directoryJson(syncedAt = 3_000)
            directory.sync()
            assertNull("the old country's businesses are dropped", directory.find(PhoneNumber("+8801812345678")).getOrThrow())
            assertEquals(null, requested.last { it.url.encodedPath == "/v1/businesses/IN" }.url.encodedQuery)
        }

    @Test
    fun aClaimStartsAndUploadsGoToThePresignedUrl() =
        runBlocking {
            val form =
                ClaimForm(
                    number = PhoneNumber("+8801812345678"),
                    legalName = " Example Bank Ltd ",
                    displayName = "Example Bank",
                    category = BusinessCategory.BANK,
                    website = "",
                    hours = null,
                    callReason = "Card security",
                    method = ClaimMethod.VOICE,
                )
            val claim = claims.start(form).getOrThrow()
            assertEquals(ClaimStatus.VERIFYING, claim.status)
            val sent = requested.single { it.url.encodedPath == "/v1/business/claims" }
            val body = sent.body!!.utf8()
            assertTrue(body, body.contains("\"legalName\":\"Example Bank Ltd\"") && body.contains("\"method\":\"voice\""))
            assertTrue("blank fields are left out", !body.contains("\"website\":\"\""))
            assertEquals("Bearer access", sent.headers["Authorization"])

            assertEquals(
                AppResult.Success("claims/c1/doc-1.pdf"),
                claims.upload("c1", ClaimFileKind.DOCUMENT, ClaimFile(ByteArray(100), "application/pdf")),
            )
            val upload = requested.single { it.url.encodedPath == "/upload/doc" }
            assertEquals("application/pdf", upload.headers["Content-Type"])
            assertEquals(
                AppResult.Failure(AppError.Validation(BusinessClaimRepository.FILE_TOO_LARGE)),
                claims.upload("c1", ClaimFileKind.DOCUMENT, ClaimFile(ByteArray(1_001), "application/pdf")),
            )
        }

    private fun uploadTarget() =
        """{"uploadUrl":"${server.url("/upload/doc")}","key":"claims/c1/doc-1.pdf","contentType":"application/pdf",""" +
            """"maxBytes":1000,"expiresInSeconds":900}"""

    private fun claim(status: String) =
        """{"id":"c1","e164":"+8801812345678","displayName":"Example Bank","category":"bank","method":"voice",""" +
            """"status":"$status","codeExpiresInSeconds":300,"createdAt":5}"""

    private fun imageResponse(bytes: ByteArray) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "image/png")
            .body(Buffer().write(bytes))
            .build()

    private fun <T> AppResult<T>.getOrThrow(): T = (this as AppResult.Success).value

    private companion object {
        const val BUSINESS = "0b6a3c52-1f0e-4f7b-9d2a-5c1e8f3a7b10"
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)

        fun entry(
            e164: String,
            withLogo: Boolean = true,
            removed: Boolean = false,
        ) = """{"e164":"$e164","businessId":"$BUSINESS","name":"Example Bank","category":"bank",""" +
            """"callReason":"Card security",${if (withLogo) "\"logoUrl\":\"/v1/business/$BUSINESS/logo\"," else ""}""" +
            """"updatedAt":1000,"removed":$removed}"""

        fun directoryJson(
            vararg entries: String,
            syncedAt: Long,
        ) = """{"countryIso":"BD","syncedAt":$syncedAt,"entries":[${entries.joinToString(",")}]}"""
    }
}
