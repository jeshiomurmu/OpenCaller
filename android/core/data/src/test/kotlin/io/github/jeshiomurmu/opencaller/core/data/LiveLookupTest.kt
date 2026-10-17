package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.data.lookup.LiveLookupRepository
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.phonenumber.BucketHasher
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The k-anonymous live lookup: bucket request, local hash match, 24 h cache, 800 ms limit (TC-LOOK-001). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class LiveLookupTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val clock = FakeClock()
    private val config = ServerConfig(server.url("/").toString())
    private val network = OpenCallerNetwork(config, MemoryTokens(), clock)
    private val number = PhoneNumber("+8801712345678")
    private val hasher = BucketHasher(BucketHasher.DEFAULT_BITS)
    private val hash = Base64.getEncoder().encodeToString(hasher.digest(number))

    private fun repository(server: ServerConfig = config) =
        LiveLookupRepository(network.lookupApi, server, PhoneNumberNormalizer(), clock, DatabaseAccess { db }, RealDispatchers)

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun bucket(entries: String) = jsonResponse(200, """{"countryCode":880,"bucket":25856,"bits":16,"entries":[$entries]}""")

    @Test
    fun findsTheNumberByItsFullHashAndCachesIt() =
        runBlocking {
            server.enqueue(
                bucket(
                    """{"hash":"AAAA","info":{"name":"Someone else","reportCount":0}},""" +
                        """{"hash":"$hash","info":{"name":"Registered Name","spamScore":0.91,"category":"loan","reportCount":214}}""",
                ),
            )
            val result = checkNotNull(repository().lookup(number))
            assertEquals(0.91f, result.spamScore!!, 1e-6f)
            assertEquals("loan", result.category)
            assertEquals(214, result.reportCount)
            assertEquals("Registered Name", result.displayName)
            assertEquals(NameSource.SELF_REGISTERED, result.nameSource)
            assertEquals(false, result.fromCache)
            // Only the calling code and the 16-bit bucket were sent (bucket 25856 = fixtures/bucket_vectors.csv).
            assertEquals("/v1/lookup/buckets/880/25856?bits=16", server.takeRequest().target)
            assertNotNull(db.lookupBucketDao().find(880, 25856))

            // Within 24 h: from the cache, no request.
            val cached = checkNotNull(repository().lookup(number))
            assertTrue(cached.fromCache)
            assertEquals(1, server.requestCount)

            // After 24 h: asked again.
            clock.advanceBy(24 * 60 * 60 * 1_000L + 1)
            server.enqueue(bucket(""))
            assertNull(repository().lookup(number)?.spamScore)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun aNumberAbsentFromItsBucketIsUnknownAndCachedAsSuch() =
        runBlocking {
            server.enqueue(bucket("""{"hash":"BBBB","info":{"reportCount":3,"spamScore":0.5}}"""))
            val result = checkNotNull(repository().lookup(number))
            assertNull(result.spamScore)
            assertNull(result.displayName)
            assertEquals(0, result.reportCount)
            repository().lookup(number)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun verifiedBusinessesComeWithTheirName() =
        runBlocking {
            server.enqueue(bucket("""{"hash":"$hash","info":{"reportCount":0,"business":{"name":"Example Bank","verified":true}}}"""))
            val result = checkNotNull(repository().lookup(number))
            assertEquals("Example Bank", result.displayName)
            assertEquals(NameSource.VERIFIED_BUSINESS, result.nameSource)
        }

    // TC-LOOK-001 (repository side): a server that answers after 2 s gives no answer, within the 800 ms limit.
    @Test
    fun aSlowServerGivesNoAnswerInTime() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .headersDelay(2, TimeUnit.SECONDS)
                    .body("""{"countryCode":880,"bucket":25856,"bits":16,"entries":[]}""")
                    .build(),
            )
            val started = System.nanoTime()
            assertNull(repository().lookup(number))
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $tookMillis ms", tookMillis < 1_500)
            // Nothing cached: the next call asks again.
            assertNull(db.numberInfoDao().findFresh(number.e164, clock.nowMillis()))
        }

    @Test
    fun offlineOrWithoutAServerThereIsNoAnswer() =
        runBlocking {
            assertNull(repository(ServerConfig(baseUrl = null)).lookup(number))
            server.close()
            assertNull(repository().lookup(number))
        }
}
