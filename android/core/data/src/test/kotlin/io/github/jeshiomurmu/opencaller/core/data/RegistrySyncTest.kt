package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.sync.LinkPrefixStore
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncOutcome
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncRepository
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncStateStore
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/** Registry updates against a local server: sender IDs, OTP sources and known-bad link prefixes (TC-OTP-003/004 data). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class RegistrySyncTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val network = OpenCallerNetwork(ServerConfig(server.url("/").toString()), MemoryTokens(), FakeClock())
    private val stateStore = RegistrySyncStateStore(tempPreferences("registry"))
    private val prefixFile = Files.createTempDirectory("prefixes").resolve(LinkPrefixStore.FILE_NAME).toFile()
    private val prefixes = LinkPrefixStore(prefixFile, RealDispatchers)
    private val repository =
        RegistrySyncRepository(network.api, DatabaseAccess { db }, RealDispatchers, stateStore, { "IN" }, prefixes)
    private val registry = SenderRegistryRepository(DatabaseAccess { db }, RealDispatchers, prefixes)
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var linkBody = """{"syncedAt":3,"prefixes":["0000000a","ffffffff","59fc7fae"]}"""

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requested += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                    return when (request.url.encodedPath) {
                        "/v1/sender-ids/IN" -> {
                            jsonResponse(
                                200,
                                """{"countryIso":"IN","syncedAt":1700,"senderIds":[{"header":"HDFCBK","brandName":"HDFC Bank",""" +
                                    """"category":"bank","source":"dlt","updatedAt":1700}]}""",
                            )
                        }

                        "/v1/otp-sources" -> {
                            jsonResponse(
                                200,
                                """{"syncedAt":900,"appHashes":[{"hash":"FA+9qCX9VSu","packageName":"org.example.pay",""" +
                                    """"appName":"Example Pay","updatedAt":900}],"verificationRanges":[{"prefix":"+1555010",""" +
                                    """"provider":"Example Flash","updatedAt":900}]}""",
                            )
                        }

                        "/v1/link-prefixes" -> {
                            jsonResponse(200, linkBody)
                        }

                        else -> {
                            jsonResponse(404, """{"code":"not_found"}""")
                        }
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
    fun registriesAreStoredAndReadOnTheDevice() =
        runBlocking {
            assertEquals(AppResult.Success(RegistrySyncOutcome(senderIds = 1, otpSources = 2, linkPrefixes = 3)), repository.sync())

            // TC-OTP-003: a registered header gives the brand; TC-OTP-004: an app hash gives the app.
            assertEquals("HDFC Bank", registry.sender("in", "hdfcbk").getOrThrow()?.brandName)
            assertEquals("Example Pay", registry.appForHash("FA+9qCX9VSu").getOrThrow())
            assertEquals("Example Flash", registry.verificationProvider(PhoneNumber("+15550100")).getOrThrow())
            assertNull(registry.verificationProvider(PhoneNumber("+15550200")).getOrThrow())
            assertTrue(registry.isBadHostPrefix(0x59fc7fae))
            assertTrue(registry.isBadHostPrefix(-1))
            assertFalse(registry.isBadHostPrefix(11))

            val state = stateStore.state.first()
            assertEquals("IN", state.senderCountry)
            assertEquals(1700L, state.senderSince)
            assertEquals(900L, state.otpSince)
        }

    @Test
    fun theNextUpdateAsksOnlyForNewerRowsAndReplacesTheLinkList() =
        runBlocking {
            repository.sync()
            linkBody = """{"syncedAt":4,"prefixes":["00000001"]}"""
            repository.sync()
            assertTrue(requested.contains("/v1/sender-ids/IN?since=1700"))
            assertTrue(requested.contains("/v1/otp-sources?since=900"))
            assertFalse(registry.isBadHostPrefix(0x59fc7fae))
            assertTrue(registry.isBadHostPrefix(1))
            // The list survives a restart (a new store reading the same file).
            assertTrue(LinkPrefixStore(prefixFile, RealDispatchers).contains(1))
        }

    @Test
    fun aMalformedPrefixListIsRefusedAndTheOldOneKept() =
        runBlocking {
            repository.sync()
            linkBody = """{"syncedAt":5,"prefixes":["xyz"]}"""
            assertEquals(AppResult.Failure(AppError.Validation("invalid_link_prefix")), repository.sync())
            assertTrue(registry.isBadHostPrefix(0x59fc7fae))
        }

    private fun <T> AppResult<T>.getOrThrow(): T = (this as AppResult.Success).value
}
