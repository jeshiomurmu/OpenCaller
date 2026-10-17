package io.github.jeshiomurmu.opencaller.server.search

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.lookup.NumberDirectory.infos
import io.github.jeshiomurmu.opencaller.server.lookup.NumberInfo
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** `GET /v1/numbers/{e164}` response. */
@Serializable
data class SearchResponse(
    val e164: String,
    val info: NumberInfo,
)

/**
 * Explicit search (docs/prd.md F-ID-03, docs/architecture.md §4.6): the user typed the number, so the server sees
 * it. Limited to 50 searches a day per user (docs/rules.md §7) and recorded as a `search` profile view of the
 * number's owner ("who searched me") unless the searcher is incognito.
 */
class SearchService(
    private val db: ServerDatabase,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** Looks up [raw] for [searcherId]. */
    suspend fun search(
        searcherId: UUID,
        raw: String,
    ): SearchResponse {
        val number = E164.parseOrNull(raw) ?: throw invalidNumber()
        enforce(rateLimiter.hit(PER_DAY, searcherId.toString()), "Daily search limit reached")
        val info =
            db.query {
                val found = infos(NumberReputation.e164 eq number.value, authenticated = true)[number.value] ?: NumberInfo()
                recordSearch(searcherId, number)
                found
            }
        return SearchResponse(number.value, info)
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.recordSearch(
        searcherId: UUID,
        number: E164,
    ) {
        val searcherIncognito =
            Users
                .select(Users.incognito)
                .where { Users.id eq searcherId }
                .singleOrNull()
                ?.get(Users.incognito) ?: true // no profile row: record nothing
        if (searcherIncognito) return
        val owner =
            Users
                .select(Users.id)
                .where { (Users.e164 eq number.value) and Users.deletedAt.isNull() }
                .singleOrNull()
                ?.get(Users.id)
        if (owner == null || owner == searcherId) return
        ProfileViews.insert {
            it[viewedUserId] = owner
            it[viewerUserId] = searcherId
            it[kind] = SEARCH_KIND
            it[at] = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        }
    }

    /** Limits. */
    companion object {
        /** docs/rules.md §7: search 50/day (free tier). */
        val PER_DAY = RateLimit("search_user", maxHits = 50, window = 1.days)

        /** `profile_views.kind` for a search. */
        const val SEARCH_KIND = "search"
    }
}
