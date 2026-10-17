package io.github.jeshiomurmu.opencaller.server.sync

import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seed files and `GET /v1/sender-ids/{countryIso}`, `GET /v1/otp-sources`. */
class SyncApiTest {
    private val seedTime = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()

    @Test
    fun theSeedFilesAreValidAndOnlyHoldFakeExamples() {
        val seed = SeedData.load()
        assertTrue(seed.senderIds.isNotEmpty() && seed.appHashes.isNotEmpty() && seed.verificationRanges.isNotEmpty())
        // Real data arrives in Phase 9; until then every row must say it is fake.
        seed.senderIds.forEach { assertTrue(it.brandName.endsWith("(fake)") && it.source == "seed-example", it.toString()) }
        seed.appHashes.forEach { assertTrue(it.appName.endsWith("(fake)") && it.packageName.startsWith("org.example."), it.toString()) }
        seed.verificationRanges.forEach { assertTrue(it.provider.endsWith("(fake)") && it.prefix.startsWith("+1555"), it.toString()) }
    }

    @Test
    fun senderIdsAreServedPerCountrySinceATime() =
        apiTest { server ->
            server.deps.sync.seedSenderIds()
            // Seeding twice is harmless.
            server.deps.sync.seedSenderIds()
            val response = client.getWith("/v1/sender-ids/bd")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
            val all = testJson.decodeFromString(SenderIdsResponse.serializer(), response.bodyAsText())
            assertEquals("BD", all.countryIso)
            assertEquals(listOf("EXMPLBANK", "EXMPLTEL"), all.senderIds.map { it.header })
            assertEquals(seedTime, all.syncedAt)
            assertEquals(SenderIdDto("EXMPLBANK", "Example Bank (fake)", "bank", "seed-example", null, seedTime), all.senderIds.first())

            val none =
                testJson.decodeFromString(
                    SenderIdsResponse.serializer(),
                    client.getWith("/v1/sender-ids/BD?since=${all.syncedAt}").bodyAsText(),
                )
            assertTrue(none.senderIds.isEmpty())
            assertEquals(all.syncedAt, none.syncedAt)
            val other = testJson.decodeFromString(SenderIdsResponse.serializer(), client.getWith("/v1/sender-ids/FR").bodyAsText())
            assertTrue(other.senderIds.isEmpty())
            client.getWith("/v1/sender-ids/ZZ").assertProblem(HttpStatusCode.BadRequest, "invalid_country")
            client.getWith("/v1/sender-ids/BD?since=-1").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.getWith("/v1/sender-ids/BD?since=yesterday").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
        }

    @Test
    fun otpSourcesAreServedSinceATime() =
        apiTest {
            val all = testJson.decodeFromString(OtpSourcesResponse.serializer(), client.getWith("/v1/otp-sources").bodyAsText())
            assertEquals(2, all.appHashes.size)
            assertEquals(AppHashDto("AAAAAAAAAA0", "org.example.fakebank", "Example Bank (fake)", seedTime), all.appHashes.first())
            assertEquals(listOf("+1555010", "+1555011"), all.verificationRanges.map { it.prefix })
            assertEquals(seedTime, all.syncedAt)
            val none =
                testJson.decodeFromString(
                    OtpSourcesResponse.serializer(),
                    client.getWith("/v1/otp-sources?since=$seedTime").bodyAsText(),
                )
            assertTrue(none.appHashes.isEmpty() && none.verificationRanges.isEmpty())
            val etag = client.getWith("/v1/otp-sources").headers[HttpHeaders.ETag]!!
            assertEquals(
                HttpStatusCode.NotModified,
                client.getWith("/v1/otp-sources", headers = mapOf(HttpHeaders.IfNoneMatch to etag)).status,
            )
        }
}
