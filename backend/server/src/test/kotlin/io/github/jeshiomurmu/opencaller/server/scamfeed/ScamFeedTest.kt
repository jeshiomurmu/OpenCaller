package io.github.jeshiomurmu.opencaller.server.scamfeed

import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The scam feed (F-PR-15): admin-curated cards per country, public, only published and unexpired ones. */
class ScamFeedTest {
    private val form = ScamCardForm("BD", "bn", "ভুয়া কুরিয়ার কল", "কোড চাইলে দেবেন না।", "", "")

    @Test
    fun appsGetThePublishedUnexpiredCardsOfTheirCountry() =
        apiTest { server ->
            val cards = server.deps.scamFeed
            val draft = assertIs<ScamCardSave.Saved>(cards.save(null, form)).id
            val published = assertIs<ScamCardSave.Saved>(cards.save(null, form.copy(language = "en", title = "Fake courier calls"))).id
            val expiring = assertIs<ScamCardSave.Saved>(cards.save(null, form.copy(title = "Short", expiresOn = "2026-10-17"))).id
            val india = assertIs<ScamCardSave.Saved>(cards.save(null, form.copy(country = "IN", language = "hi"))).id
            listOf(published, expiring, india).forEach { assertTrue(cards.setPublished(it, true)) }

            val response = client.getWith("/v1/scam-feed/bd")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
            val feed = testJson.decodeFromString(ScamFeed.serializer(), response.bodyAsText())
            assertEquals("BD", feed.country)
            assertEquals(setOf(published.toString(), expiring.toString()), feed.cards.map { it.id }.toSet())
            assertTrue(draft.toString() !in response.bodyAsText())

            // A card is shown through its last day, then no more.
            server.clock.advance(2.days)
            val later = testJson.decodeFromString(ScamFeed.serializer(), client.getWith("/v1/scam-feed/BD").bodyAsText())
            assertEquals(listOf(published.toString()), later.cards.map { it.id })
            client.getWith("/v1/scam-feed/B1").assertProblem(HttpStatusCode.BadRequest, "invalid_country")
        }

    @Test
    fun cardsAreCheckedBeforeTheyAreSaved() {
        val server = TestServer()
        runBlocking {
            server.reset()
            val cards = server.deps.scamFeed

            fun invalid(card: ScamCardForm) = assertIs<ScamCardSave.Invalid>(runBlocking { cards.save(null, card) })
            invalid(form.copy(country = "Bangladesh"))
            invalid(form.copy(language = "Bengali"))
            invalid(form.copy(title = ""))
            invalid(form.copy(title = "two\nlines"))
            invalid(form.copy(body = "x".repeat(ScamFeedService.MAX_BODY + 1)))
            invalid(form.copy(link = "http://not-https.example"))
            invalid(form.copy(link = "https://x.example/\"><script>"))
            invalid(form.copy(expiresOn = "2026-10-15"))
            invalid(form.copy(expiresOn = "tomorrow"))
            assertIs<ScamCardSave.Saved>(cards.save(null, form.copy(link = "https://www.bb.org.bd/", expiresOn = "2026-12-31")))
            assertIs<ScamCardSave.Invalid>(cards.save(java.util.UUID.randomUUID(), form))
        }
    }
}
