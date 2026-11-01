package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.lookup.NumberDirectory.infos
import io.github.jeshiomurmu.opencaller.server.profile.Visits.approvedFor
import io.github.jeshiomurmu.opencaller.server.profile.Visits.ownerOf
import io.github.jeshiomurmu.opencaller.server.profile.Visits.record
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Profile views (docs/prd.md F-SO-02/03): opening someone's profile records a `view` under the rules of [Visits];
 * supporters can list who viewed and who searched for them in the last 90 days. A visitor's name is listed only
 * as their own visibility allows (the owner is signed in, so `everyone` and `registered_only` names show), never
 * their number (docs/decisions/0004-viewer-searcher-logging.md).
 */
class VisitorService(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val entitlements: Entitlements,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /**
     * What [viewerId] may see of [raw]'s profile, recorded as a `view`. The answer has the same shape whether or
     * not the number has a profile, so it doesn't reveal who is registered.
     */
    suspend fun profile(
        viewerId: UUID,
        raw: String,
    ): PublicProfileResponse {
        val number = E164.parseOrNull(raw) ?: throw invalidNumber()
        enforce(rateLimiter.hit(VIEWS_PER_DAY, viewerId.toString()), "Daily profile view limit reached")
        val (name, owner) =
            db.query {
                val owner = ownerOf(number.value)
                val listed = infos(NumberReputation.e164 eq number.value, authenticated = true)[number.value]?.name
                record(viewerId, owner, VisitKind.VIEW, now())
                val granted = owner?.takeIf { listed == null && it.displayName != null && approvedFor(viewerId, it) }
                (listed ?: granted?.displayName) to owner
            }
        val shown = owner?.takeIf { name != null }
        return PublicProfileResponse(
            e164 = number.value,
            name = name,
            avatarUrl = shown?.avatarKey?.let { objects.presignGet(it, ProfileService.AVATAR_URL_SECONDS).toString() },
            badge = shown?.tier?.badge,
        )
    }

    /** The newest [LIST_LIMIT] visits of [kind] to [userId]'s profile in the last 90 days; 403 for non-supporters. */
    suspend fun visitors(
        userId: UUID,
        kind: VisitKind,
    ): VisitorsResponse {
        db.query { activeUser(userId) }
        if (!entitlements.isSupporter(userId)) throw supporterRequired()
        val since = now().minusDays(ProfileService.VISIT_DAYS)
        val (total, rows) =
            db.query {
                val query =
                    ProfileViews
                        .join(Users, JoinType.INNER, ProfileViews.viewerUserId, Users.id)
                        .select(ProfileViews.at, Users.displayName, Users.visibility, Users.supporterTier)
                        .where {
                            (ProfileViews.viewedUserId eq userId) and (ProfileViews.kind eq kind.value) and
                                (ProfileViews.at greaterEq since)
                        }
                query.count().toInt() to query.orderBy(ProfileViews.at, SortOrder.DESC).limit(LIST_LIMIT).toList()
            }
        val items =
            rows.map { row ->
                val name = row[Users.displayName]?.takeIf { row[Users.visibility] != Visibility.NOBODY }
                VisitorItem(
                    name = name,
                    badge =
                        name?.let {
                            SupporterTier.of(row[Users.supporterTier]).badge
                        },
                    at = row[ProfileViews.at].toInstant().toString(),
                )
            }
        return VisitorsResponse(kind.value, ProfileService.VISIT_DAYS.toInt(), total, items)
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** Profiles a user may open per day (not in docs/rules.md §7; it keeps profile scraping slow). */
        val VIEWS_PER_DAY = RateLimit("profile_views_user", maxHits = 100, window = 1.days)

        /** Visits listed per request (the total counts all of them). */
        const val LIST_LIMIT = 200
    }
}
