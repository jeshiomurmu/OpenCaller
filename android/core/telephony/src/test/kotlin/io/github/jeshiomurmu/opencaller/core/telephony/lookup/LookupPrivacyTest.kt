package io.github.jeshiomurmu.opencaller.core.telephony.lookup

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DefaultDispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.lookup.LiveLookupRepository
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import io.github.jeshiomurmu.opencaller.core.phonenumber.BucketHasher
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.FakeSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.Collections
import kotlin.time.TimeSource

/**
 * TC-LOOK-002 (docs/architecture.md §10, invariant 2): during a screened incoming call no request path, query or body
 * contains the caller's number, in E.164, without the `+`, or in national form. Only the country calling code and the
 * 16-bit bucket leave the phone; the full hash is matched on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class LookupPrivacyTest {
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val server = MockWebServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenCallerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val caller = PhoneNumber("+8801712345678")
    private val normalizer = PhoneNumberNormalizer()
    private val hash = Base64.getEncoder().encodeToString(BucketHasher(BucketHasher.DEFAULT_BITS).digest(caller))

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun startServer(score: Double) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """{"countryCode":880,"bucket":25856,"bits":16,"entries":[""" +
                                """{"hash":"$hash","info":{"name":"Spam Caller","spamScore":$score,""" +
                                """"category":"scam","reportCount":99}}]}""",
                        ).build()
                }
            }
        server.start()
    }

    private fun engine(signedIn: Boolean): ScreeningEngine {
        val clock = Clock { 1_767_225_600_000L }
        val tokens = MemoryTokens(if (signedIn) AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE) else null)
        val config = ServerConfig(server.url("/").toString())
        val network = OpenCallerNetwork(config, tokens, clock)
        val repository =
            LiveLookupRepository(network.lookupApi, config, normalizer, clock, DatabaseAccess { db }, DefaultDispatcherProvider)
        val sources = FakeSources().apply { this.network = ServerNetworkLookup(repository) }
        return ScreeningEngine(
            sources.device(),
            sources.lists(),
            normalizer,
            DefaultDispatcherProvider,
            TimeSource.Monotonic,
            ScreeningConfig(),
        )
    }

    @Test
    fun tcLook002NoRequestContainsTheNumber() {
        startServer(score = 0.95)
        listOf(false, true).forEach { signedIn ->
            db.clearAllTables()
            val result = runBlocking { engine(signedIn).screen(ScreeningCall(caller.e164, Direction.INCOMING)) }
            // The lookup worked: the community score blocks the call.
            assertEquals(Decision.BLOCK, result.decision)
            assertEquals(ScreeningReason.NETWORK, result.reason)
        }
        assertEquals(2, requests.size)
        val forbidden =
            listOf(
                caller.e164,
                caller.e164.removePrefix("+"),
                // National forms, formatted and as plain digits.
                normalizer.format(caller, "BD"),
                normalizer.format(caller, "BD").filter(Char::isDigit),
                "1712345678",
            ).distinct()
        requests.forEach { request ->
            val path = request.url.encodedPath
            val query = request.url.encodedQuery.orEmpty()
            val body = request.body?.utf8().orEmpty()
            val decoded = java.net.URLDecoder.decode(request.target, Charsets.UTF_8)
            forbidden.forEach { form ->
                assertFalse("'$form' in $path", path.contains(form))
                assertFalse("'$form' in ?$query", query.contains(form))
                assertFalse("'$form' in body", body.contains(form))
                assertFalse("'$form' in $decoded", decoded.contains(form))
            }
            assertEquals("/v1/lookup/buckets/880/25856", path)
            assertEquals("bits=16", query)
            assertTrue(body.isEmpty())
        }
    }
}

private class MemoryTokens(
    private var tokens: AuthTokens?,
) : TokenStore {
    override suspend fun read(): AuthTokens? = tokens

    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
