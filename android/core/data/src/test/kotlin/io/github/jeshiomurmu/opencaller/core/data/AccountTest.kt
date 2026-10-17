package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountRepository
import io.github.jeshiomurmu.opencaller.core.data.account.KeystoreTokenStore
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuotaStore
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchRepository
import io.github.jeshiomurmu.opencaller.core.database.crypto.PassphraseCipher
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

/** Sign-in with encrypted token storage, and search with its daily quota. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AccountTest {
    private val server = MockWebServer().apply { start() }
    private val clock = FakeClock()
    private val cipher = XorCipher()
    private val tokens = KeystoreTokenStore(tempPreferences("tokens"), cipher)
    private val config = ServerConfig(server.url("/").toString(), flavor = "foss")
    private val network = OpenCallerNetwork(config, tokens, clock)
    private val work = RecordingBackgroundWork()
    private val account = AccountRepository(network.api, tokens, config, clock, work)
    private val search = SearchRepository(network.api, SearchQuotaStore(tempPreferences("quota")), clock)
    private val me = PhoneNumber("+8801712345678")

    @After
    fun tearDown() = server.close()

    @Test
    fun signInStoresEncryptedTokensAndSendsQueuedReports() =
        runBlocking {
            assertFalse(account.signedIn.first())
            server.enqueue(jsonResponse(202, """{"expiresInSeconds":300}"""))
            assertEquals(AppResult.Success(300L), account.sendCode(me))
            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessToken":"a.b.c","accessTokenExpiresIn":900,""" +
                        """"refreshToken":"r1","refreshTokenExpiresIn":2592000,"tokenType":"Bearer"}""",
                ),
            )
            assertEquals(AppResult.Success(Unit), account.verify(me, "123456"))
            assertTrue(account.signedIn.first())
            assertEquals(AuthTokens("a.b.c", clock.nowMillis() + 900_000, "r1", clock.nowMillis() + 2_592_000_000), tokens.read())
            assertTrue(cipher.encrypted > 0)
            assertEquals(listOf(0L), work.uploads)
            server.takeRequest()
            assertEquals("""{"e164":"+8801712345678","code":"123456","flavor":"foss"}""", server.takeRequest().body?.utf8())
            account.signOut()
            assertNull(tokens.read())
            assertFalse(account.signedIn.first())
        }

    @Test
    fun aWrongCodeIsAValidationError() =
        runBlocking {
            server.enqueue(jsonResponse(400, """{"code":"invalid_code","detail":"4 attempts left."}"""))
            assertEquals(AppResult.Failure(AppError.Validation("invalid_code")), account.verify(me, "000000"))
            assertFalse(account.signedIn.first())
        }

    @Test
    fun undecryptableTokensSignOut() =
        runBlocking {
            tokens.write(AuthTokens("a", 1, "r", 2))
            cipher.broken = true
            assertNull(tokens.read())
            cipher.broken = false
            assertNull(tokens.read())
        }

    @Test
    fun searchCountsAgainstTheDailyQuota() =
        runBlocking {
            tokens.write(AuthTokens("a", Long.MAX_VALUE, "r", Long.MAX_VALUE))
            assertEquals(50, search.quota.first().remaining)
            server.enqueue(jsonResponse(200, """{"e164":"+12015550123","info":{"name":"Someone","reportCount":2,"spamScore":0.43}}"""))
            val found = (search.search(PhoneNumber("+12015550123")) as AppResult.Success).value
            assertEquals("Someone", found.displayName)
            assertEquals(2, found.reportCount)
            assertEquals(49, search.quota.first().remaining)
            assertEquals("/v1/numbers/+12015550123", server.takeRequest().url.encodedPath)
            // The server says the quota is used up: none left until Retry-After.
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "600")
                    .build(),
            )
            assertEquals(AppResult.Failure(AppError.RateLimited(600)), search.search(PhoneNumber("+12015550123")))
            val quota = search.quota.first()
            assertEquals(0, quota.remaining)
            assertEquals(clock.nowMillis() + 600_000, quota.blockedUntil)
            // A day later everything is back.
            clock.advanceBy(24 * 60 * 60 * 1_000L + 1)
            assertEquals(50, search.quota.first().remaining)
        }

    @Test
    fun withoutAServerNothingWorksButNothingBreaks() =
        runBlocking {
            val offline = ServerConfig(baseUrl = null)
            val net = OpenCallerNetwork(offline, tokens, clock)
            val repo = AccountRepository(net.api, tokens, offline, clock, work)
            assertFalse(repo.serverConfigured)
            assertEquals(AppResult.Failure(AppError.ServerNotConfigured), repo.sendCode(me))
        }
}

/** Reversible stand-in for the Keystore cipher (the Android Keystore isn't available under Robolectric). */
class XorCipher : PassphraseCipher {
    var encrypted = 0
    var broken = false

    override fun encrypt(plaintext: ByteArray): ByteArray {
        encrypted++
        return plaintext.map { (it.toInt() xor KEY).toByte() }.toByteArray()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (broken) throw GeneralSecurityException("key lost")
        return ciphertext.map { (it.toInt() xor KEY).toByte() }.toByteArray()
    }

    private companion object {
        const val KEY = 0x5A
    }
}
