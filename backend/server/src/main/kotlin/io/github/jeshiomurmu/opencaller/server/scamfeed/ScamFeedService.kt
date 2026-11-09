package io.github.jeshiomurmu.opencaller.server.scamfeed

import io.github.jeshiomurmu.opencaller.server.db.ScamCards
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One card of the scam feed as apps get it. */
@Serializable
data class ScamCardView(
    val id: String,
    val language: String,
    val title: String,
    val body: String,
    val link: String? = null,
    val updatedAt: String,
)

/** `GET /v1/scam-feed/{iso}`: the published cards of [country], newest first; the app picks its language. */
@Serializable
data class ScamFeed(
    val country: String,
    val cards: List<ScamCardView>,
)

/** A card as the admin web shows and edits it. */
data class ScamCard(
    val id: UUID,
    val country: String,
    val language: String,
    val title: String,
    val body: String,
    val link: String?,
    val published: Boolean,
    val expiresOn: LocalDate?,
    val updatedAt: OffsetDateTime,
)

/** What an admin typed into the card form, unchecked. */
data class ScamCardForm(
    val country: String,
    val language: String,
    val title: String,
    val body: String,
    val link: String,
    val expiresOn: String,
)

/** The outcome of saving a card: its id, or why it was refused (a sentence for the admin). */
sealed interface ScamCardSave {
    /** Saved as [id]. */
    data class Saved(
        val id: UUID,
    ) : ScamCardSave

    /** Not saved. */
    data class Invalid(
        val reason: String,
    ) : ScamCardSave
}

/**
 * The scam feed (docs/prd.md F-PR-15): short cards about the scams going around in a country, written and
 * published by an admin in the admin web. Apps fetch their country's cards without signing in (the request names
 * only the country) and show them in the Protection tab.
 */
class ScamFeedService(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** The published, unexpired cards of [rawCountry]. */
    suspend fun feed(rawCountry: String): ScamFeed {
        val country = rawCountry.takeIf(ISO::matches) ?: throw invalidCountry()
        val now = OffsetDateTime.now(clock)
        val cards =
            db.query {
                ScamCards
                    .selectAll()
                    .where {
                        (ScamCards.countryIso eq country) and (ScamCards.published eq true) and
                            (ScamCards.expiresAt.isNull() or (ScamCards.expiresAt greater now))
                    }.orderBy(ScamCards.updatedAt, SortOrder.DESC)
                    .limit(MAX_CARDS)
                    .map { row ->
                        ScamCardView(
                            id = row[ScamCards.id].toString(),
                            language = row[ScamCards.language],
                            title = row[ScamCards.title],
                            body = row[ScamCards.body],
                            link = row[ScamCards.link],
                            updatedAt = row[ScamCards.updatedAt].toInstant().toString(),
                        )
                    }
            }
        return ScamFeed(country, cards)
    }

    /** Every card (of [country] when given), for the admin web. */
    suspend fun all(country: String? = null): List<ScamCard> =
        db.query {
            val filter = country?.takeIf(ISO::matches)
            ScamCards
                .selectAll()
                .let { query -> if (filter == null) query else query.where { ScamCards.countryIso eq filter } }
                .orderBy(ScamCards.countryIso to SortOrder.ASC, ScamCards.updatedAt to SortOrder.DESC)
                .map(::toCard)
        }

    /** Card [id], or `null`. */
    suspend fun card(id: UUID): ScamCard? =
        db.query {
            ScamCards
                .selectAll()
                .where { ScamCards.id eq id }
                .singleOrNull()
                ?.let(::toCard)
        }

    /** Saves [form] as a new unpublished card, or over card [id]. */
    suspend fun save(
        id: UUID?,
        form: ScamCardForm,
    ): ScamCardSave {
        problemWith(form)?.let { return ScamCardSave.Invalid(it) }
        val expires =
            form.expiresOn
                .takeIf { it.isNotBlank() }
                ?.let(::dateOrNull)
                ?.plusDays(1)
                ?.atStartOfDay()
                ?.atOffset(ZoneOffset.UTC)
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val link = form.link.takeIf { it.isNotBlank() }
        return db.query {
            if (id == null) {
                val newId = UUID.randomUUID()
                ScamCards.insert {
                    it[ScamCards.id] = newId
                    it[countryIso] = form.country
                    it[language] = form.language
                    it[title] = form.title
                    it[body] = form.body
                    it[ScamCards.link] = link
                    it[expiresAt] = expires
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                ScamCardSave.Saved(newId)
            } else {
                val changed =
                    ScamCards.update({ ScamCards.id eq id }) {
                        it[countryIso] = form.country
                        it[language] = form.language
                        it[title] = form.title
                        it[body] = form.body
                        it[ScamCards.link] = link
                        it[expiresAt] = expires
                        it[updatedAt] = now
                    }
                if (changed == 0) ScamCardSave.Invalid("That card no longer exists.") else ScamCardSave.Saved(id)
            }
        }
    }

    /** Publishes or withdraws card [id]; `false` if there is no such card. */
    suspend fun setPublished(
        id: UUID,
        published: Boolean,
    ): Boolean =
        db.query {
            ScamCards.update({ ScamCards.id eq id }) {
                it[ScamCards.published] = published
                it[updatedAt] = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            } > 0
        }

    /** Deletes card [id]; `false` if there was none. */
    suspend fun delete(id: UUID): Boolean = db.query { ScamCards.deleteWhere { ScamCards.id eq id } > 0 }

    private fun problemWith(form: ScamCardForm): String? =
        when {
            !ISO.matches(form.country) -> "The country must be a two-letter code such as BD."
            !LANGUAGE.matches(form.language) -> "The language must be a code such as en, bn or pt-BR."
            !(oneLine(form.title) && isPlainText(form.title, MAX_TITLE)) -> "The title must be 1 to $MAX_TITLE characters on one line."
            !isPlainText(form.body, MAX_BODY) -> "The text must be 1 to $MAX_BODY characters."
            form.link.isNotBlank() && !isLink(form.link) -> "The link must be an https:// address of at most $MAX_LINK characters."
            form.expiresOn.isNotBlank() && !isFutureDate(form.expiresOn) -> "The end date must be a date from today on, such as 2027-01-31."
            else -> null
        }

    private fun isFutureDate(text: String): Boolean = dateOrNull(text)?.let { !it.isBefore(LocalDate.now(clock)) } ?: false

    private fun toCard(row: ResultRow) =
        ScamCard(
            id = row[ScamCards.id],
            country = row[ScamCards.countryIso],
            language = row[ScamCards.language],
            title = row[ScamCards.title],
            body = row[ScamCards.body],
            link = row[ScamCards.link],
            published = row[ScamCards.published],
            expiresOn = row[ScamCards.expiresAt]?.toLocalDate()?.minusDays(1),
            updatedAt = row[ScamCards.updatedAt],
        )

    /** Field rules. */
    companion object {
        /** A country as stored (ISO 3166-1 alpha-2, upper case). */
        val ISO = Regex("[A-Z]{2}")

        /** A language tag: a 2–3 letter language, optionally with a region (`pt-BR`). */
        val LANGUAGE = Regex("[a-z]{2,3}(-[A-Z]{2})?")
        const val MAX_TITLE = 80
        const val MAX_BODY = 600
        const val MAX_LINK = 200
        private const val MAX_CARDS = 50

        private fun oneLine(text: String) = text.trim() == text && '\n' !in text

        // A form date that doesn't parse is reported to the admin as a field problem, not an error.
        private fun dateOrNull(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

        private fun isLink(url: String) =
            url.length <= MAX_LINK && url.startsWith("https://") && url.length > "https://".length &&
                url.none { it.isWhitespace() || it.isISOControl() || it == '"' || it == '<' || it == '>' }

        private fun invalidCountry() =
            ApiProblem(HttpStatusCode.BadRequest, "invalid_country", "Unknown country", "Use an ISO 3166-1 alpha-2 code such as BD.")
    }
}
