package io.github.jeshiomurmu.opencaller.server.lists

import io.github.jeshiomurmu.opencaller.server.config.MinioConfig
import io.github.jeshiomurmu.opencaller.server.db.ListVersions
import io.github.jeshiomurmu.opencaller.server.storage.S3ObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.MinioContainer
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertNotSpamReports
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The list builder with a real MinIO, and the manifest and file endpoints. */
class ListBuilderTest {
    private val minioServer by lazy {
        val minio = TestInfra.minio
        val config =
            testConfig().let {
                it.copy(minio = MinioConfig(minio.endpoint, MinioContainer.ACCESS_KEY, MinioContainer.SECRET_KEY, bucket = "lists-test"))
            }
        TestServer(objectStore = S3ObjectStore(config.minio, java.time.Clock.systemUTC()), config = config)
    }

    private suspend fun versions() =
        TestInfra.database.query {
            ListVersions
                .selectAll()
                .orderBy(
                    ListVersions.countryIso to SortOrder.ASC,
                    ListVersions.version to SortOrder.ASC,
                    ListVersions.kind to SortOrder.ASC,
                ).map { Triple(it[ListVersions.countryIso], it[ListVersions.version], it[ListVersions.kind]) }
        }

    @Test
    fun buildsFullListsAndDeltasPerCountryInMinio() =
        apiTest(minioServer) { server ->
            // BD: a clear spammer, a number just over the 0.4 list threshold, one below it and a clean one; US: one spammer.
            insertReports("+8801712345678", 20, trust = 1.0f, at = server.clock.now, category = "loan")
            insertReports("+8801812345678", 1, trust = 0.5f, at = server.clock.now)
            insertReports("+8801912345678", 1, trust = 0.25f, at = server.clock.now)
            insertNotSpamReports("+88027111234", 3, trust = 1.0f, at = server.clock.now)
            // An hour old, so the next scoring run (which re-reads the last 5 minutes before its watermark) leaves it alone.
            insertReports("+12015550123", 5, trust = 1.0f, at = server.clock.now.minusSeconds(3600))
            server.deps.scoringJob.run()

            val first = server.deps.listBuilder.build()
            assertEquals(listOf("BD" to 2, "US" to 1), first.built.map { it.countryIso to it.entries })
            assertEquals(listOf(Triple("BD", 1L, "full"), Triple("US", 1L, "full")), versions())

            val bd1 = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 1, ListKind.FULL))!!)
            assertEquals(listOf("+8801712345678", "+8801812345678"), bd1.entries.map { it.e164 })
            // (1 + 20) / (3 + 20) = 0.913 → 91 %; (1 + 0.5) / (3 + 0.5) = 0.4286 → 42 %; (1 + 0.25) / (3 + 0.25) = 0.385 → not listed.
            assertEquals(listOf(91, 42), bd1.entries.map { it.scorePercent })
            assertEquals("loan", bd1.entries[0].category)
            assertEquals(20, bd1.entries[0].reportCount)

            // Nothing changed: no new versions.
            val again = server.deps.listBuilder.build()
            assertTrue(again.built.isEmpty())
            assertEquals(2, again.unchanged)

            // +8801712345678 is cleared by not-spam reports, +88027111234 becomes spam, the US number is unchanged.
            server.clock.advance(1.days)
            insertNotSpamReports("+8801712345678", 60, trust = 1.0f, at = server.clock.now)
            insertReports("+88027111234", 30, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            val second = server.deps.listBuilder.build()
            assertEquals(listOf("BD"), second.built.map { it.countryIso })
            assertEquals(2L, second.built.single().version)

            val bd2 = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 2, ListKind.FULL))!!)
            val delta = ListFormat.decode(server.objectStore.get(ListBuilder.objectKey("BD", 2, ListKind.DELTA))!!)
            assertEquals(ListKind.DELTA, delta.kind)
            assertEquals(1L, delta.baseVersion)
            assertEquals(listOf(8_801_712_345_678), delta.removals)
            // Applying the delta to v1 gives exactly v2.
            val applied =
                (
                    bd1.entries.associateBy {
                        it.number
                    } - delta.removals.toSet() + delta.entries.associateBy { it.number }
                ).values.sortedBy { it.number }
            assertEquals(bd2.entries, applied)
        }

    @Test
    fun manifestAndFilesAreServedWithChecksums() =
        apiTest(minioServer) { server ->
            client.getWith("/v1/lists/BD/manifest").assertProblem(HttpStatusCode.NotFound, "list_not_found")
            insertReports("+8801712345678", 20, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            server.deps.listBuilder.build()
            server.clock.advance(1.days)
            insertReports("+8801812345678", 20, trust = 1.0f, at = server.clock.now)
            server.deps.scoringJob.run()
            server.deps.listBuilder.build()

            val response = client.getWith("/v1/lists/bd/manifest")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
            val manifest = testJson.decodeFromString(ListManifest.serializer(), response.bodyAsText())
            assertEquals("BD", manifest.countryIso)
            assertEquals(2L, manifest.version)
            assertEquals(ListFormat.FORMAT_VERSION, manifest.format)
            assertEquals("/v1/lists/BD/files/2/full", manifest.full.url)
            assertNull(manifest.full.baseVersion)
            assertEquals(listOf(1L to 2L), manifest.deltas.map { it.baseVersion to it.version })

            (listOf(manifest.full) + manifest.deltas).forEach { ref ->
                val file = client.getWith(ref.url)
                assertEquals(HttpStatusCode.OK, file.status)
                val bytes = file.bodyAsBytes()
                assertEquals(ref.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
                assertEquals("public, max-age=31536000, immutable", file.headers[HttpHeaders.CacheControl])
                assertEquals("\"${ref.sha256}\"", file.headers[HttpHeaders.ETag])
                assertEquals(
                    HttpStatusCode.NotModified,
                    client
                        .getWith(
                            ref.url,
                            headers =
                                mapOf(HttpHeaders.IfNoneMatch to "\"${ref.sha256}\""),
                        ).status,
                )
            }
            assertEquals(2, ListFormat.decode(client.getWith(manifest.full.url).bodyAsBytes()).entries.size)

            client.getWith("/v1/lists/BD/files/9/full").assertProblem(HttpStatusCode.NotFound, "list_not_found")
            client.getWith("/v1/lists/BD/files/1/delta").assertProblem(HttpStatusCode.NotFound, "list_not_found")
            client.getWith("/v1/lists/BD/files/x/full").assertProblem(HttpStatusCode.NotFound, "list_not_found")
            client.getWith("/v1/lists/BD/files/1/extended").assertProblem(HttpStatusCode.NotFound, "list_not_found")
            client.getWith("/v1/lists/XX/manifest").assertProblem(HttpStatusCode.BadRequest, "invalid_country")
            client.getWith("/v1/lists/BGD/manifest").assertProblem(HttpStatusCode.BadRequest, "invalid_country")
        }
}
