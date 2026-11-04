package io.github.jeshiomurmu.opencaller.server.lists

import io.github.jeshiomurmu.opencaller.server.config.BillingConfig
import io.github.jeshiomurmu.opencaller.server.config.ListConfig
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** F-PR-03: the standard list keeps the most-reported numbers up to a cap; the rest is the extended list for supporters. */
class ExtendedListTest {
    private fun server(selfHosted: Boolean = false) =
        TestServer(
            config =
                testConfig().copy(
                    lists = ListConfig(standardMaxEntries = 2),
                    billing = BillingConfig(selfHosted = selfHosted),
                ),
        )

    // Four BD spammers with 20, 10, 3 and 1 reports (trust 1.0, so every one scores at least 0.5).
    private suspend fun seed(server: TestServer) {
        listOf("+8801712345678" to 20, "+8801812345678" to 10, "+8801912345678" to 3, "+8801612345678" to 1).forEach { (number, count) ->
            insertReports(number, count, trust = 1.0f, at = server.clock.now)
        }
        server.deps.scoringJob.run()
    }

    private suspend fun ApplicationTestBuilder.manifest(): ListManifest {
        val response = client.getWith("/v1/lists/BD/manifest")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(ListManifest.serializer(), response.bodyAsText())
    }

    @Test
    fun theMostReportedNumbersStayStandardAndTheRestAreExtended() =
        apiTest(server()) { server ->
            seed(server)
            val build = server.deps.listBuilder.build()
            assertEquals(listOf(Triple("BD", 2, 2)), build.built.map { Triple(it.countryIso, it.entries, it.extendedEntries) })

            val standard = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 1, ListKind.FULL))!!)
            assertEquals(listOf("+8801712345678", "+8801812345678"), standard.entries.map { it.e164 })
            val extended = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 1, ListKind.EXTENDED))!!)
            assertEquals(ListKind.EXTENDED, extended.kind)
            assertEquals(listOf("+8801612345678", "+8801912345678"), extended.entries.map { it.e164 })

            // Unchanged: no new version. A new spammer with more reports pushes the third-placed one into the extended list.
            assertTrue(
                server.deps.listBuilder
                    .build()
                    .built
                    .isEmpty(),
            )
            server.clock.advance(1.days)
            insertReports("+8801512345678", 15, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            server.deps.listBuilder.build()
            val v2 = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 2, ListKind.FULL))!!)
            assertEquals(listOf("+8801512345678", "+8801712345678"), v2.entries.map { it.e164 })
            val delta = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 2, ListKind.DELTA))!!)
            assertEquals(listOf(8801812345678L), delta.removals)
            val extendedV2 = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 2, ListKind.EXTENDED))!!)
            assertEquals(listOf("+8801612345678", "+8801812345678", "+8801912345678"), extendedV2.entries.map { it.e164 })
        }

    @Test
    fun onlySupportersDownloadTheExtendedList() =
        apiTest(server()) { server ->
            seed(server)
            server.deps.listBuilder.build()
            val manifest = manifest()
            val ref = assertNotNull(manifest.extended)
            assertEquals("/v1/lists/BD/files/1/extended", ref.url)
            assertNull(ref.baseVersion)

            client.getWith(ref.url).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            val user = signIn(server, "+14155550101").accessToken
            client.getWith(ref.url, user).assertProblem(HttpStatusCode.Forbidden, "supporter_required")

            setSupporterTier("+14155550101", "supporter")
            val file = client.getWith(ref.url, user)
            assertEquals(HttpStatusCode.OK, file.status)
            assertEquals("private, max-age=31536000, immutable", file.headers[HttpHeaders.CacheControl])
            val bytes = file.bodyAsBytes()
            assertEquals(ref.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertEquals(2, ListFormat.decode(bytes).entries.size)
            client.getWith("/v1/lists/BD/files/7/extended", user).assertProblem(HttpStatusCode.NotFound, "list_not_found")
        }

    @Test
    fun aSelfHostedServerServesTheExtendedListToEveryone() =
        apiTest(server(selfHosted = true)) { server ->
            seed(server)
            server.deps.listBuilder.build()
            val ref = assertNotNull(manifest().extended)
            assertEquals(HttpStatusCode.OK, client.getWith(ref.url).status)
        }

    @Test
    fun aCountryUnderTheCapHasNoExtendedList() =
        apiTest(TestServer()) { server ->
            seed(server)
            server.deps.listBuilder.build()
            assertNull(manifest().extended)
            assertNull(server.objectStore.get(ListBuilder.objectKey("BD", 1, ListKind.EXTENDED)))
        }
}
