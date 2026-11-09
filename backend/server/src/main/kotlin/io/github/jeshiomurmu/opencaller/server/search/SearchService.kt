package io.github.jeshiomurmu.opencaller.server.search

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.lookup.NumberDirectory.infos
import io.github.jeshiomurmu.opencaller.server.lookup.NumberInfo
import io.github.jeshiomurmu.opencaller.server.profile.VisitKind
import io.github.jeshiomurmu.opencaller.server.profile.Visits.approvedFor
import io.github.jeshiomurmu.opencaller.server.profile.Visits.ownerOf
import io.github.jeshiomurmu.opencaller.server.profile.Visits.record
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.search.BusinessSearchCounter.countSearch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
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
 * it. Limited to 50 searches a day per user (docs/rules.md §7) and recorded as a `search` visit of the number's
 * owner ("who searched for me") under the rules of [Visits]. An owner who approved the searcher's contact request
 * shows their name even when their visibility would hide it. A search of a verified-business number also adds
 * one to that number's daily count for the business dashboard ([BusinessSearchCounter]), without the searcher.
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
                val owner = ownerOf(number.value)
                val found = infos(NumberReputation.e164 eq number.value, authenticated = true)[number.value] ?: NumberInfo()
                val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                record(searcherId, owner, VisitKind.SEARCH, now)
                countSearch(number.value, now.toLocalDate())
                if (found.name == null && owner?.displayName != null && approvedFor(searcherId, owner)) {
                    found.copy(name = owner.displayName, badge = owner.tier.badge)
                } else {
                    found
                }
            }
        return SearchResponse(number.value, info)
    }

    /** Limits. */
    companion object {
        /** docs/rules.md §7: search 50/day (free tier). */
        val PER_DAY = RateLimit("search_user", maxHits = 50, window = 1.days)

        /** `profile_views.kind` for a search. */
        val SEARCH_KIND = VisitKind.SEARCH.value
    }
}
