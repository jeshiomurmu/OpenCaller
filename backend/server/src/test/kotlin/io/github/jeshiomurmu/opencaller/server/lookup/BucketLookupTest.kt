package io.github.jeshiomurmu.opencaller.server.lookup

import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertBusiness
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.insertUser
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /v1/lookup/buckets/{cc}/{bucketId}`: bucket contents and caching (TC-API-007), visibility (TC-API-008). */
class BucketLookupTest {
    private class Vector(
        val e164: String,
        val bits: Int,
        val sha256Hex: String,
        val bucket: Int,
    ) {
        val hashBase64: String = Base64.getEncoder().encodeToString(sha256Hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    // The vectors the app's BucketHasher is tested with (CI checks both copies are identical).
    private val vectors =
        javaClass
            .getResource("/fixtures/bucket_vectors.csv")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(',').let { c -> Vector(c[0], c[1].toInt(), c[2], c[3].toInt()) } }

    private suspend fun HttpResponse.entries(): BucketResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return testJson.decodeFromString(BucketResponse.serializer(), bodyAsText())
    }

    private fun callingCode(e164: String) = NumberKey.of(e164).countryCode

    // TC-API-007, with the app's test vectors: each number is found in its bucket under its full hash.
    @Test
    fun everyVectorIsFoundInItsBucketWithItsFullHash() =
        apiTest { server ->
            val numbers = vectors.map { it.e164 }.distinct()
            numbers.forEach { e164 -> insertReports(e164, 1, at = server.clock.now) }
            server.deps.scoringJob.run()
            vectors.filter { it.bits <= BucketHasher.DEFAULT_BITS }.forEach { v ->
                val bucket = client.getWith("/v1/lookup/buckets/${callingCode(v.e164)}/${v.bucket}?bits=${v.bits}").entries()
                assertEquals(v.bucket, bucket.bucket)
                val entry = bucket.entries.singleOrNull { it.hash == v.hashBase64 }
                assertNotNull(entry, "${v.e164} not in bucket ${v.bucket}/${v.bits}: ${bucket.entries}")
                assertEquals(1, entry.info.reportCount)
            }
        }

    // TC-API-007: only the requested bucket of the requested country calling code.
    @Test
    fun onlyEntriesOfTheRequestedBucketAreReturned() =
        apiTest { server ->
            listOf("+8801712345678", "+8801812345678", "+88027111234", "+12015550123").forEach { e164 ->
                insertReports(e164, 3, at = server.clock.now)
            }
            server.deps.scoringJob.run()
            val bucket = client.getWith("/v1/lookup/buckets/880/25856").entries()
            assertEquals(listOf(vector("+8801712345678").hashBase64), bucket.entries.map { it.hash })
            val info = bucket.entries.single().info
            assertEquals(3, info.reportCount)
            assertEquals("scam", info.category)
            // (1 + 1.5) / (3 + 1.5)
            assertEquals(0.556, info.spamScore)
            assertNull(info.label)
            // Same bucket number in another country code: empty.
            assertTrue(
                client
                    .getWith("/v1/lookup/buckets/1/25856")
                    .entries()
                    .entries
                    .isEmpty(),
            )
            assertTrue(
                client
                    .getWith("/v1/lookup/buckets/880/25857")
                    .entries()
                    .entries
                    .isEmpty(),
            )
            // A 12-bit bucket spans 16 stored buckets: 1616 covers 25856..25871.
            assertEquals(
                1,
                client
                    .getWith("/v1/lookup/buckets/880/1616?bits=12")
                    .entries()
                    .entries.size,
            )
        }

    // TC-API-007: ETag and 304.
    @Test
    fun etagsAllowConditionalRequests() =
        apiTest { server ->
            insertReports("+8801712345678", 3, at = server.clock.now)
            server.deps.scoringJob.run()
            val first = client.getWith("/v1/lookup/buckets/880/25856")
            val etag = assertNotNull(first.headers[HttpHeaders.ETag])
            assertEquals("public, max-age=86400", first.headers[HttpHeaders.CacheControl])
            assertEquals(HttpHeaders.Authorization, first.headers[HttpHeaders.Vary])
            val again = client.getWith("/v1/lookup/buckets/880/25856", headers = mapOf(HttpHeaders.IfNoneMatch to etag))
            assertEquals(HttpStatusCode.NotModified, again.status)
            assertEquals("", again.bodyAsText())
            assertEquals(etag, again.headers[HttpHeaders.ETag])
            assertEquals(
                HttpStatusCode.NotModified,
                client.getWith("/v1/lookup/buckets/880/25856", headers = mapOf(HttpHeaders.IfNoneMatch to "\"other\", W/$etag")).status,
            )
            // New data, new ETag.
            insertReports("+8801712345678", 1, at = server.clock.now)
            server.deps.scoringJob.run()
            val changed = client.getWith("/v1/lookup/buckets/880/25856", headers = mapOf(HttpHeaders.IfNoneMatch to etag))
            assertEquals(HttpStatusCode.OK, changed.status)
            assertTrue(changed.headers[HttpHeaders.ETag] != etag)
        }

    // TC-API-008: `nobody` never appears; `registered_only` names only with a token; `everyone` always.
    @Test
    fun profileVisibilityIsRespected() =
        apiTest { server ->
            insertUser("+8801712345678", "Everyone Person", Visibility.EVERYONE)
            insertUser("+8801812345678", "Registered Person", Visibility.REGISTERED_ONLY)
            insertUser("+88027111234", "Nobody Person", Visibility.NOBODY)
            val token = signIn(server, "+12015550123").accessToken

            suspend fun names(token: String?): Map<String, String?> =
                listOf("+8801712345678", "+8801812345678", "+88027111234")
                    .associateWith { e164 ->
                        client
                            .getWith("/v1/lookup/buckets/880/${vector(e164).bucket}", token)
                            .entries()
                            .entries
                            .singleOrNull { it.hash == vector(e164).hashBase64 }
                            ?.info
                            ?.name
                    }.filterValues { it != null }

            assertEquals(mapOf("+8801712345678" to "Everyone Person"), names(null))
            assertEquals(mapOf("+8801712345678" to "Everyone Person", "+8801812345678" to "Registered Person"), names(token))
            val withToken = client.getWith("/v1/lookup/buckets/880/25856", token)
            assertEquals("private, max-age=86400", withToken.headers[HttpHeaders.CacheControl])

            // With spam reports the `nobody` number is listed for its spam data, still without a name.
            insertReports("+88027111234", 2, at = server.clock.now)
            server.deps.scoringJob.run()
            val nobody =
                client
                    .getWith("/v1/lookup/buckets/880/${vector("+88027111234").bucket}", token)
                    .entries()
                    .entries
                    .single()
            assertNull(nobody.info.name)
            assertEquals(2, nobody.info.reportCount)
        }

    @Test
    fun verifiedBusinessesAppearWithTheirName() =
        apiTest {
            insertBusiness("+8801712345678", "Example Bank")
            insertBusiness("+8801812345678", "Pending Bank", ReviewStatus.PENDING)
            it.deps.scoringJob.run()
            val business =
                client
                    .getWith("/v1/lookup/buckets/880/25856")
                    .entries()
                    .entries
                    .single()
                    .info.business
            assertEquals(BusinessInfo("Example Bank", "bank", verified = true, callReason = "Account security"), business)
            assertTrue(
                client
                    .getWith("/v1/lookup/buckets/880/50696")
                    .entries()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun invalidRequestsAreRejected() =
        apiTest {
            listOf(
                "/v1/lookup/buckets/abc/1",
                "/v1/lookup/buckets/0/1",
                "/v1/lookup/buckets/999/1",
                "/v1/lookup/buckets/880/65536",
                "/v1/lookup/buckets/880/-1",
                "/v1/lookup/buckets/880/x",
                "/v1/lookup/buckets/880/4096?bits=12",
                "/v1/lookup/buckets/880/1?bits=11",
                "/v1/lookup/buckets/880/1?bits=20",
                "/v1/lookup/buckets/880/1?bits=x",
            ).forEach { path -> client.getWith(path).assertProblem(HttpStatusCode.BadRequest, "invalid_bucket") }
            // A token that is sent must be valid.
            client.getWith("/v1/lookup/buckets/880/1", "expired-or-forged").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    private fun vector(e164: String) = vectors.first { it.e164 == e164 && it.bits == BucketHasher.DEFAULT_BITS }
}
