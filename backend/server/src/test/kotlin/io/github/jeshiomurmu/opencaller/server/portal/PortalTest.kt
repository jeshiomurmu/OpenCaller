package io.github.jeshiomurmu.opencaller.server.portal

import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.BusinessSearchCounts
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertBusiness
import io.github.jeshiomurmu.opencaller.server.testing.insertNotSpamReports
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The business dashboard (F-BZ-04): code sign-in, counts the server already holds, profile edits with CSRF tokens. */
class PortalTest {
    private fun ApplicationTestBuilder.browser(): HttpClient =
        createClient {
            install(HttpCookies)
            followRedirects = false
        }

    // Like a browser: posts the form and follows a "303 See Other" with a GET.
    private suspend fun HttpClient.form(
        path: String,
        vararg fields: Pair<String, String>,
    ): HttpResponse {
        val response = submitForm(path, parameters { fields.forEach { (name, value) -> append(name, value) } })
        val next = response.headers[HttpHeaders.Location]
        return if (response.status == HttpStatusCode.SeeOther && next != null) get(next) else response
    }

    private suspend fun HttpClient.signInAs(
        server: TestServer,
        number: String,
    ): String {
        assertEquals(HttpStatusCode.OK, form("/portal/login", "e164" to number).status)
        val dashboard = form("/portal/verify", "e164" to number, "code" to server.otp.lastCodeFor(number))
        assertEquals(HttpStatusCode.OK, dashboard.status, dashboard.bodyAsText())
        return dashboard.bodyAsText()
    }

    private fun csrfOf(html: String): String = checkNotNull(Regex("name=\"csrf\" value=\"([^\"]+)\"").find(html)).groupValues[1]

    @Test
    fun onlyAVerifiedBusinessNumberGetsACodeAndASession() =
        apiTest { server ->
            insertBusiness(BANK, "Acme Bank")
            insertBusiness(PENDING, "Pending Shop", status = ReviewStatus.PENDING)
            val browser = browser()
            val start = browser.get("/portal")
            assertTrue("Business dashboard" in start.bodyAsText())
            assertEquals("DENY", start.headers["X-Frame-Options"])
            assertTrue("default-src 'none'" in start.headers["Content-Security-Policy"].orEmpty())

            // The same answer for a number without a dashboard, but no code is sent to it.
            val pending = browser.form("/portal/login", "e164" to PENDING)
            assertTrue("Enter the code" in pending.bodyAsText())
            assertTrue(server.otp.sent.none { it.first.value == PENDING })
            browser.form("/portal/verify", "e164" to PENDING, "code" to "123456").let { assertTrue("Enter the code" in it.bodyAsText()) }

            browser.form("/portal/login", "e164" to BANK)
            val wrong = browser.form("/portal/verify", "e164" to BANK, "code" to wrongCode(server.otp.lastCodeFor(BANK)))
            assertTrue("Enter the code" in wrong.bodyAsText())
            assertTrue(browser.get("/portal").bodyAsText().contains("Send code"))

            val dashboard = browser.form("/portal/verify", "e164" to BANK, "code" to server.otp.lastCodeFor(BANK))
            assertTrue("Acme Bank" in dashboard.bodyAsText(), dashboard.bodyAsText())
            assertTrue("Acme Bank" in browser.get("/portal").bodyAsText()) // the session cookie came back

            // A business that is no longer approved loses its sessions.
            TestInfra.database.query { Businesses.update({ Businesses.displayName eq "Acme Bank" }) { it[status] = ReviewStatus.REJECTED } }
            assertTrue("Send code" in browser.get("/portal").bodyAsText())
        }

    @Test
    fun theDashboardCountsSearchesReportsAndDisputesWithoutWhoMadeThem() =
        apiTest { server ->
            insertBusiness(BANK, "Acme Bank")
            val searcher = signIn(server, SEARCHER).accessToken
            repeat(2) { assertEquals(HttpStatusCode.OK, client.getWith("/v1/numbers/$BANK", searcher).status) }
            client.getWith("/v1/numbers/$PRIVATE", searcher)
            insertReports(BANK, 3, at = server.clock.now)
            insertNotSpamReports(BANK, 1, at = server.clock.now)
            TestInfra.database.query {
                Disputes.insert {
                    it[e164] = BANK
                    it[reason] = "Secret reason written by the disputer"
                    it[createdAt] = OffsetDateTime.ofInstant(server.clock.now, ZoneOffset.UTC)
                }
            }
            val counted =
                TestInfra.database.query {
                    BusinessSearchCounts.selectAll().map {
                        it[BusinessSearchCounts.e164] to
                            it[BusinessSearchCounts.searches]
                    }
                }
            assertEquals(listOf(BANK to 2), counted) // a number that isn't a verified business isn't counted

            val html = browser().signInAs(server, BANK)
            val row = Regex("<tr><td>2026-10-16</td><td>\\$BANK</td><td>(\\d+)</td>\\s*<td>(\\d+)</td><td>(\\d+)</td></tr>").find(html)
            assertEquals(listOf("2", "3", "1"), checkNotNull(row) { html }.groupValues.drop(1))
            assertTrue("Waiting for review" in html)
            assertFalse("Secret reason" in html)
            assertFalse(SEARCHER in html)
            assertTrue("never learns" in html) // no call-outcome telemetry, said in plain words
        }

    @Test
    fun profileEditsNeedTheSessionsFormToken() =
        apiTest { server ->
            insertBusiness(BANK, "Acme Bank")
            val browser = browser()
            browser.signInAs(server, BANK)
            val form = browser.get("/portal/profile").bodyAsText()
            val csrf = csrfOf(form)

            val forged = browser.form("/portal/profile", "website" to "https://evil.example", "csrf" to "forged")
            assertEquals(HttpStatusCode.Forbidden, forged.status)
            val bad = browser.form("/portal/profile", "website" to "http://acme.example", "csrf" to csrf)
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue("https://" in bad.bodyAsText())

            val saved =
                browser.form(
                    "/portal/profile",
                    "csrf" to csrf,
                    "website" to "https://acme.example",
                    "hours" to "Sun–Thu 9–17",
                    "reason:$BANK" to "Card security check",
                    "reason:$PRIVATE" to "Not ours",
                )
            assertEquals(HttpStatusCode.OK, saved.status)
            assertTrue("Saved." in saved.bodyAsText())
            val business = TestInfra.database.query { Businesses.selectAll().single() }
            assertEquals("https://acme.example", business[Businesses.website])
            assertEquals("Sun–Thu 9–17", business[Businesses.hours])
            val number = TestInfra.database.query { BusinessNumbers.selectAll().single() }
            assertEquals("Card security check", number[BusinessNumbers.defaultCallReason])
            // Touched, so apps pick the business up with their next directory update.
            assertEquals(server.clock.now, number[BusinessNumbers.updatedAt].toInstant())
            assertEquals(server.clock.now, business[Businesses.updatedAt].toInstant())

            val signOut = browser.form("/portal/logout", "csrf" to csrf)
            assertTrue("Send code" in signOut.bodyAsText())
            val profile = browser.get("/portal/profile")
            assertEquals(HttpStatusCode.Found, profile.status)
            assertEquals("/portal", profile.headers[HttpHeaders.Location])
        }

    private fun wrongCode(code: String) = if (code == "000000") "111111" else "000000"

    private companion object {
        const val BANK = "+8809610000001"
        const val PENDING = "+8809610000002"
        const val PRIVATE = "+8801711000009"
        const val SEARCHER = "+8801711000010"
    }
}
