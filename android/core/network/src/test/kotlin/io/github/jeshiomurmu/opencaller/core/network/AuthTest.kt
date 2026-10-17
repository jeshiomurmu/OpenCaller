package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Access tokens on the right requests only, serialized refresh on 401, sign-out when the refresh token is refused. */
class AuthTest {
    private val server = MockWebServer()
    private val tokens = MemoryTokenStore()
    private val now = 1_000_000L
    private lateinit var network: OpenCallerNetwork

    @Before
    fun setUp() {
        server.start()
        network = OpenCallerNetwork(ServerConfig(server.url("/").toString(), userAgent = "OpenCaller/test"), tokens, { now })
    }

    @After
    fun tearDown() = server.close()

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private val searchBody = """{"e164":"+12015550123","info":{"reportCount":2}}"""
    private val tokenBody =
        """{"accessToken":"new-access","accessTokenExpiresIn":900,""" +
            """"refreshToken":"new-refresh","refreshTokenExpiresIn":2592000}"""

    @Test
    fun tokensGoOnlyToEndpointsThatUseThem() =
        runBlocking {
            tokens.write(AuthTokens("access-1", now + 1, "refresh-1", now + 2))
            server.enqueue(json(200, searchBody))
            server.enqueue(json(202, """{"expiresInSeconds":300}"""))
            server.enqueue(json(200, """{"countryCode":1,"bucket":7,"bits":16,"entries":[]}"""))
            apiCall { network.api.search("+12015550123") }
            apiCall { network.api.startSignIn(StartRequest("+12015550123")) }
            apiCall { network.lookupApi.bucket(1, 7, 16) }
            val search = server.takeRequest()
            assertEquals("Bearer access-1", search.headers["Authorization"])
            assertNull(search.headers[AUTH_MODE_HEADER])
            assertEquals("OpenCaller/test", search.headers["User-Agent"])
            assertNull(server.takeRequest().headers["Authorization"])
            // Optional on lookups: sent while signed in (registered-only names), never required.
            assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        }

    @Test
    fun signedOutRequestsGoWithoutAToken() =
        runBlocking {
            server.enqueue(json(200, """{"countryCode":1,"bucket":7,"bits":16,"entries":[]}"""))
            assertTrue(apiCall { network.lookupApi.bucket(1, 7, 16) } is AppResult.Success)
            assertNull(server.takeRequest().headers["Authorization"])
        }

    @Test
    fun anExpiredAccessTokenIsRefreshedAndTheRequestRetried() =
        runBlocking {
            tokens.write(AuthTokens("old-access", now, "old-refresh", now + 1))
            server.enqueue(json(401, """{"code":"unauthorized"}"""))
            server.enqueue(json(200, tokenBody))
            server.enqueue(json(200, searchBody))
            val result = apiCall { network.api.search("+12015550123") }
            assertTrue(result is AppResult.Success)
            assertEquals("Bearer old-access", server.takeRequest().headers["Authorization"])
            val refresh = server.takeRequest()
            assertEquals("/v1/auth/refresh", refresh.url.encodedPath)
            assertNull(refresh.headers["Authorization"])
            assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
            assertEquals(AuthTokens("new-access", now + 900_000, "new-refresh", now + 2_592_000_000), tokens.read())
        }

    @Test
    fun aRefusedRefreshTokenSignsTheUserOut() =
        runBlocking {
            tokens.write(AuthTokens("old-access", now, "reused", now + 1))
            server.enqueue(json(401, """{"code":"unauthorized"}"""))
            server.enqueue(json(401, """{"code":"refresh_token_reused"}"""))
            assertEquals(AppResult.Failure(AppError.Auth), apiCall { network.api.search("+12015550123") })
            assertNull(tokens.read())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun concurrentFailuresRefreshOnce() =
        runBlocking {
            tokens.write(AuthTokens("old-access", now, "old-refresh", now + 1))
            val refreshes = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.url.encodedPath == "/v1/auth/refresh" -> {
                                refreshes.incrementAndGet()
                                Thread.sleep(200)
                                json(200, tokenBody)
                            }

                            request.headers["Authorization"] == "Bearer new-access" -> {
                                json(200, searchBody)
                            }

                            else -> {
                                json(401, """{"code":"unauthorized"}""")
                            }
                        }
                }
            val results = List(5) { async(Dispatchers.IO) { apiCall { network.api.search("+12015550123") } } }.awaitAll()
            assertTrue(results.all { it is AppResult.Success })
            assertEquals(1, refreshes.get())
        }

    @Test
    fun errorsMapToAppErrors() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "86000")
                    .build(),
            )
            server.enqueue(json(400, """{"code":"invalid_phone_number","status":400}"""))
            server.enqueue(json(404, """{"code":"list_not_found"}"""))
            server.enqueue(json(500, """{"code":"internal_error"}"""))
            assertEquals(AppResult.Failure(AppError.RateLimited(86_000)), apiCall { network.api.search("+1") })
            assertEquals(AppResult.Failure(AppError.Validation("invalid_phone_number")), apiCall { network.api.search("+1") })
            assertEquals(AppResult.Failure(AppError.NotFound), apiCall { network.api.listManifest("BD") })
            assertTrue((apiCall { network.api.listManifest("BD") } as AppResult.Failure).error is AppError.Unexpected)
            server.close()
            assertEquals(AppResult.Failure(AppError.Network), apiCall { network.api.listManifest("BD") })
        }

    @Test
    fun withoutAServerNothingIsSent() =
        runBlocking {
            val offline = OpenCallerNetwork(ServerConfig(baseUrl = null), tokens, { now })
            assertEquals(AppResult.Failure(AppError.ServerNotConfigured), apiCall { offline.api.listManifest("BD") })
            assertEquals(AppResult.Failure(AppError.ServerNotConfigured), apiCall { offline.lookupApi.bucket(1, 1, 16) })
            assertEquals(false, offline.config.isConfigured)
            assertNull(offline.config.unlistPageUrl)
            assertEquals("http://10.0.2.2:8080/unlist", ServerConfig("http://10.0.2.2:8080").unlistPageUrl)
        }
}

/** Tokens in memory. */
class MemoryTokenStore : TokenStore {
    @Volatile private var tokens: AuthTokens? = null

    override suspend fun read(): AuthTokens? = tokens

    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
