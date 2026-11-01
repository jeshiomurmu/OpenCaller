package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ContactRequests
import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.Reports
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.lookup.BucketResponse
import io.github.jeshiomurmu.opencaller.server.lookup.NumberKey
import io.github.jeshiomurmu.opencaller.server.search.SearchResponse
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `DELETE /v1/me` and the retention job's purge (docs/prd.md F-SO-07, TC-SOC-003). */
class DeleteAccountTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val number = "+8801712345678"
    private val friend = "+12015550123"
    private val spammer = "+8801999000111"

    private suspend fun rows(table: org.jetbrains.exposed.v1.core.Table) = TestInfra.database.query { table.selectAll().count() }

    // TC-SOC-003: the profile is gone from every answer at once; the purge job removes the rest.
    @Test
    fun deletionIsImmediateAndThePurgeRemovesTheRest() =
        apiTest(server) {
            val tokens = signIn(server, number)
            val token = tokens.accessToken
            val friendToken = signIn(server, friend).accessToken
            val upload =
                testJson.decodeFromString(
                    AvatarUploadResponse.serializer(),
                    client.postJson("/v1/me/avatar", "{}", mapOf(HttpHeaders.Authorization to "Bearer $token")).bodyAsText(),
                )
            store.upload(upload.avatarKey, ByteArray(10), "image/jpeg")
            val body = """{"displayName":"Asha","visibility":"everyone","incognito":false,"avatarKey":"${upload.avatarKey}"}"""
            assertEquals(HttpStatusCode.OK, client.putJson("/v1/me", body, token).status)
            // A report Asha wrote, a search of her and a view she made.
            insertReports(spammer, 2, at = server.clock.now)
            val userId = TestInfra.database.query { Users.selectAll().where { Users.e164 eq number }.single()[Users.id] }
            TestInfra.database.query {
                Reports.insert {
                    it[e164] = spammer
                    it[reporterId] = userId
                    it[label] = ReportLabel.SPAM
                    it[createdAt] = server.clock.now.atOffset(ZoneOffset.UTC)
                }
            }
            server.deps.scoringJob.run()
            val scoreBefore = score()
            client.getWith("/v1/numbers/$number", friendToken)
            client.getWith("/v1/profiles/$friend", token)

            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/me", token).status)

            // Immediately: no name anywhere, no session, no views.
            val key = NumberKey.of(number)
            val bucket =
                testJson.decodeFromString(
                    BucketResponse.serializer(),
                    client.getWith("/v1/lookup/buckets/${key.countryCode}/${key.bucket16}").bodyAsText(),
                )
            assertTrue(bucket.entries.none { it.info.name == "Asha" })
            val search =
                testJson.decodeFromString(
                    SearchResponse.serializer(),
                    client.getWith("/v1/numbers/$number", friendToken).bodyAsText(),
                )
            assertNull(search.info.name)
            client.getWith("/v1/me", token).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            client
                .postJson("/v1/auth/refresh", """{"refreshToken":"${tokens.refreshToken}"}""")
                .assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
            assertEquals(0, TestInfra.database.query { Devices.selectAll().where { Devices.userId eq userId }.count() })
            assertEquals(0, rows(ProfileViews))
            assertTrue(store.objects.containsKey(upload.avatarKey), "the picture waits for the purge")

            // The purge job: the user's report, picture and row go; the number they reported gets rescored.
            val result = server.deps.retentionJob.run()
            assertEquals(1, result.accountsPurged)
            assertEquals(1, result.reportsDeleted)
            assertEquals(0, TestInfra.database.query { Users.selectAll().where { Users.id eq userId }.count() })
            assertTrue(!store.objects.containsKey(upload.avatarKey))
            assertEquals(2, TestInfra.database.query { Reports.selectAll().count() })
            server.deps.scoringJob.run()
            assertNotEquals(scoreBefore, score())
        }

    @Test
    fun signingInAgainBeforeThePurgeStartsANewAccount() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            client.putJson("/v1/me", """{"displayName":"Asha","visibility":"everyone","incognito":true}""", token)
            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/me", token).status)
            val again = signIn(server, number).accessToken
            val me = testJson.decodeFromString(MeResponse.serializer(), client.getWith("/v1/me", again).bodyAsText())
            assertNull(me.displayName)
            assertEquals("registered_only", me.visibility)
            assertEquals(false, me.incognito)
            // The old account is still purged; the new one stays.
            assertEquals(
                1,
                server.deps.retentionJob
                    .run()
                    .accountsPurged,
            )
            assertEquals(HttpStatusCode.OK, client.getWith("/v1/me", again).status)
            assertEquals(0, rows(ContactRequests))
        }

    @Test
    fun deletingTwiceIsRefused() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/me", token).status)
            client.deleteWith("/v1/me", token).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    private suspend fun score(): Float =
        TestInfra.database.query {
            NumberReputation.selectAll().where { NumberReputation.e164 eq spammer }.single()[NumberReputation.spamScore]
        }
}
