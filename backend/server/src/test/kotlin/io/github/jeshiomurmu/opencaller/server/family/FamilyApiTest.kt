package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyInvites
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationView
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Family protection (TC-FAM-001, TC-FAM-002): consent on the member's own phone, alerts and counts to the admin. */
class FamilyApiTest {
    private val pushes = RecordingPushSender()

    private suspend fun ApplicationTestBuilder.create(
        token: String,
        name: String = "Rahman family",
        label: String = "Mum",
    ): HttpResponse = client.postJson("/v1/family", """{"name":"$name","label":"$label"}""", bearer(token))

    private suspend fun ApplicationTestBuilder.invite(token: String): String {
        val response = client.postJson("/v1/family/invites", "{}", bearer(token))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(FamilyInvite.serializer(), response.bodyAsText()).code
    }

    private suspend fun ApplicationTestBuilder.join(
        token: String,
        code: String,
        label: String = "Ama",
        consent: Boolean = true,
        shares: Boolean = true,
    ): HttpResponse {
        val sharing = "\"shareOtpAlerts\":$shares,\"shareDailyCounts\":$shares"
        val body = """{"code":"$code","label":"$label","consent":$consent,"consentVersion":${FamilyRules.CONSENT_VERSION},$sharing}"""
        return client.postJson("/v1/family/join", body, bearer(token))
    }

    private suspend fun ApplicationTestBuilder.view(token: String): FamilyView {
        val response = client.getWith("/v1/family", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(FamilyView.serializer(), response.bodyAsText())
    }

    @Test
    fun tcFam001MembersJoinOnlyByTheirOwnConsent() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            val ama = signIn(it, AMA).accessToken
            assertEquals(HttpStatusCode.Created, create(mum).status)
            client.getWith("/v1/family", ama).assertProblem(HttpStatusCode.NotFound, "not_in_family")

            // The admin can only make a code; Ama decides on her own phone, after seeing what joining means.
            val code = invite(mum)
            val preview =
                testJson.decodeFromString(
                    FamilyInvitePreview.serializer(),
                    client.getWith("/v1/family/invites/$code", ama).bodyAsText(),
                )
            assertEquals("Mum", preview.adminLabel)
            assertEquals(1, preview.members)
            assertEquals(FamilyRules.CONSENT_VERSION, preview.consentVersion)
            assertEquals(1, view(mum).memberCount) // looking at an invite changes nothing

            join(ama, code, consent = false).assertProblem(HttpStatusCode.BadRequest, "consent_required")
            val outdated =
                """{"code":"$code","label":"Ama","consent":true,"consentVersion":99,""" +
                    """"shareOtpAlerts":true,"shareDailyCounts":true}"""
            client.postJson("/v1/family/join", outdated, bearer(ama)).assertProblem(HttpStatusCode.Conflict, "consent_outdated")
            join(mum, code).assertProblem(HttpStatusCode.Conflict, "already_in_family")
            assertEquals(1, view(mum).memberCount)

            val joined = join(ama, code.lowercase().chunked(4).joinToString("-"))
            assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
            val amaView = testJson.decodeFromString(FamilyView.serializer(), joined.bodyAsText())
            assertEquals("member", amaView.me.role)
            assertEquals(FamilyRules.CONSENT_VERSION, amaView.me.consentVersion)
            assertEquals(it.clock.now.toString(), amaView.me.consentedAt)
            assertTrue(amaView.members.isEmpty()) // a member sees the group and themselves, not the others
            assertEquals(listOf("Mum", "Ama"), view(mum).members.map { m -> m.label })

            // A code works once.
            val carol = signIn(it, CAROL).accessToken
            join(carol, code).assertProblem(HttpStatusCode.NotFound, "invite_not_found")
            client.getWith("/v1/family/invites/$code", carol).assertProblem(HttpStatusCode.NotFound, "invite_not_found")
            client.getWith("/v1/family/invites/NOTACODE", carol).assertProblem(HttpStatusCode.NotFound, "invite_not_found")
            // Members don't invite; one group per person.
            client.postJson("/v1/family/invites", "{}", bearer(ama)).assertProblem(HttpStatusCode.Forbidden, "family_admin_only")
            create(ama).assertProblem(HttpStatusCode.Conflict, "already_in_family")
        }

    @Test
    fun aGroupHoldsFivePeopleAndCodesExpire() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            create(mum)
            listOf(AMA, CAROL, DAN, EVE).forEach { number ->
                assertEquals(HttpStatusCode.OK, join(signIn(it, number).accessToken, invite(mum)).status)
            }
            assertEquals(5, view(mum).memberCount)
            client.postJson("/v1/family/invites", "{}", bearer(mum)).assertProblem(HttpStatusCode.Conflict, "family_full")

            // A code made before the group filled up is refused when used.
            client.deleteWith("/v1/family/members/${view(mum).members.last().id}", mum)
            val late = invite(mum)
            join(signIn(it, FRED).accessToken, invite(mum))
            join(signIn(it, GUS).accessToken, late).assertProblem(HttpStatusCode.Conflict, "family_full")

            // Codes last 48 hours.
            client.deleteWith("/v1/family/members/${view(mum).members.last().id}", mum)
            val old = invite(mum)
            it.clock.advance(49.hours)
            join(signIn(it, HAL).accessToken, old).assertProblem(HttpStatusCode.NotFound, "invite_not_found")
        }

    @Test
    fun tcFam002AnAlertReachesTheAdminAsAnOpaquePush() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            val ama = signIn(it, AMA).accessToken
            assertEquals(HttpStatusCode.NoContent, client.putJson("/v1/push", """{"type":"fcm","token":"mum-phone"}""", mum).status)
            create(mum)
            join(ama, invite(mum))

            val sent = client.postJson("/v1/family/alerts", """{"kind":"otp_during_call"}""", bearer(ama))
            assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
            assertEquals(1, testJson.decodeFromString(FamilyAlertSent.serializer(), sent.bodyAsText()).reachedDevices)
            val push = pushes.to("mum-phone").single()
            assertTrue(push.urgent)
            assertEquals(FamilyAlertService.ALERT_TTL_SECONDS, push.ttlSeconds)

            val item = client.getWith("/v1/notifications/${push.itemId}", mum)
            assertEquals(HttpStatusCode.OK, item.status, item.bodyAsText())
            val alert = assertNotNull(testJson.decodeFromString(NotificationView.serializer(), item.bodyAsText()).familyAlert)
            assertEquals("Ama", alert.memberLabel)
            assertEquals("otp_during_call", alert.kind)
            // Only the kind, the member's label and the time: no number, no message text.
            assertFalse(AMA in item.bodyAsText())
            assertEquals(listOf("otp_during_call"), view(mum).alerts.map { a -> a.kind })
            // Ama's phone gets nothing back about it; nobody else can read it.
            client.getWith("/v1/notifications/${push.itemId}", ama).assertProblem(HttpStatusCode.NotFound, "notification_not_found")
        }

    @Test
    fun onlyWhatAMemberSharesReachesTheAdmin() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            val ama = signIn(it, AMA).accessToken
            create(mum)
            join(ama, invite(mum), shares = false)
            client
                .postJson(
                    "/v1/family/alerts",
                    """{"kind":"otp_burst"}""",
                    bearer(ama),
                ).assertProblem(HttpStatusCode.Forbidden, "not_shared")
            client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":3,"spamMessages":1}""", ama).assertProblem(
                HttpStatusCode.Forbidden,
                "not_shared",
            )
            client
                .postJson(
                    "/v1/family/alerts",
                    """{"kind":"otp_burst"}""",
                    bearer(mum),
                ).assertProblem(HttpStatusCode.Forbidden, "not_shared")
            client.postJson("/v1/family/alerts", """{"kind":"number +8801711111111"}""", bearer(ama)).assertProblem(
                HttpStatusCode.BadRequest,
                "invalid_request",
            )

            client.putJson("/v1/family/me", """{"label":"Ama","shareOtpAlerts":true,"shareDailyCounts":true}""", ama)
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":3,"spamMessages":1}""", ama).status,
            )
            client.putJson("/v1/family/counts/2026-10-15", """{"blockedCalls":2,"spamMessages":0}""", ama)
            client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":4,"spamMessages":1}""", ama) // the day again: replaced
            client.putJson("/v1/family/counts/2026-10-10", """{"blockedCalls":1,"spamMessages":0}""", ama).assertProblem(
                HttpStatusCode.BadRequest,
                "invalid_request",
            )
            client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":-1,"spamMessages":0}""", ama).assertProblem(
                HttpStatusCode.BadRequest,
                "invalid_request",
            )
            val days = view(mum).members.single { m -> m.label == "Ama" }.days
            assertEquals(listOf(FamilyDayCounts("2026-10-16", 4, 1), FamilyDayCounts("2026-10-15", 2, 0)), days)
            client.postJson("/v1/family/alerts", """{"kind":"otp_burst"}""", bearer(ama))

            // Turning sharing off deletes what the server holds of it.
            client.putJson("/v1/family/me", """{"label":"Ama","shareOtpAlerts":false,"shareDailyCounts":false}""", ama)
            assertTrue(
                view(mum)
                    .members
                    .single { m -> m.label == "Ama" }
                    .days
                    .isEmpty(),
            )
            assertTrue(view(mum).alerts.isEmpty())
            assertEquals(0L, TestInfra.database.query { FamilyDailyCounts.selectAll().count() + FamilyAlerts.selectAll().count() })
        }

    @Test
    fun leavingAndRemovingDeleteWhatTheServerHeld() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            val ama = signIn(it, AMA).accessToken
            val carol = signIn(it, CAROL).accessToken
            create(mum)
            join(ama, invite(mum))
            join(carol, invite(mum), label = "Carol")
            client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":3,"spamMessages":1}""", ama)
            client.postJson("/v1/family/alerts", """{"kind":"otp_during_call"}""", bearer(carol))

            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/family/me", ama).status)
            client.getWith("/v1/family", ama).assertProblem(HttpStatusCode.NotFound, "not_in_family")
            assertEquals(listOf("Mum", "Carol"), view(mum).members.map { m -> m.label })
            client
                .deleteWith(
                    "/v1/family/members/${view(mum).members.first().id}",
                    carol,
                ).assertProblem(HttpStatusCode.Forbidden, "family_admin_only")

            // The admin leaving ends the group for everyone.
            invite(mum)
            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/family/me", mum).status)
            client.getWith("/v1/family", carol).assertProblem(HttpStatusCode.NotFound, "not_in_family")
            val left =
                TestInfra.database.query {
                    FamilyDailyCounts.selectAll().count() + FamilyAlerts.selectAll().count() + FamilyInvites.selectAll().count()
                }
            assertEquals(0L, left)
        }

    @Test
    fun theExpiryJobDropsOldAlertsCountsAndInvites() =
        apiTest(TestServer(pushSender = pushes)) {
            val mum = signIn(it, MUM).accessToken
            val ama = signIn(it, AMA).accessToken
            create(mum)
            join(ama, invite(mum))
            invite(mum)
            client.putJson("/v1/family/counts/2026-10-16", """{"blockedCalls":3,"spamMessages":1}""", ama)
            client.postJson("/v1/family/alerts", """{"kind":"otp_during_call"}""", bearer(ama))

            val early = it.deps.expiryJob.run()
            assertEquals(
                ExpiryResult(familyInvites = 1, familyAlerts = 0, familyCounts = 0, businessSearchDays = 0, portalSessions = 0),
                early,
            )
            it.clock.advance(31.days)
            val late = it.deps.expiryJob.run()
            assertEquals(1, late.familyInvites)
            assertEquals(1, late.familyAlerts)
            assertEquals(1, late.familyCounts)
        }

    private companion object {
        const val MUM = "+8801711000001"
        const val AMA = "+8801711000002"
        const val CAROL = "+8801711000003"
        const val DAN = "+8801711000004"
        const val EVE = "+8801711000005"
        const val FRED = "+8801711000006"
        const val GUS = "+8801711000007"
        const val HAL = "+8801711000008"
    }
}
