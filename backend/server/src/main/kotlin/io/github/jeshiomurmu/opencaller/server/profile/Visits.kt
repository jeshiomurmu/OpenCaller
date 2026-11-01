package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ContactRequests
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.Users
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.OffsetDateTime
import java.util.UUID

/** `profile_views.kind`. */
enum class VisitKind(
    val value: String,
) {
    /** Someone opened the profile (`GET /v1/profiles/{e164}`). */
    VIEW("view"),

    /** Someone searched for the number (`GET /v1/numbers/{e164}`). */
    SEARCH("search"),
}

/** The owner of a number: an undeleted user. */
data class NumberOwner(
    val id: UUID,
    val displayName: String?,
    val avatarKey: String?,
    val tier: SupporterTier,
)

/**
 * The rules for "who viewed my profile" and "who searched for me" (docs/decisions/0004-viewer-searcher-logging.md).
 * Only who and when is stored, never more. Nothing is recorded when the viewer is incognito, has no profile row,
 * looks at their own number, or the number has no undeleted owner; and at most one row per viewer, owner and kind
 * in 24 hours, so repeated looks don't flood the list.
 */
object Visits {
    private const val DEDUPE_HOURS = 24L

    /** The undeleted user who owns [e164], if any. */
    fun JdbcTransaction.ownerOf(e164: String): NumberOwner? =
        Users
            .select(Users.id, Users.displayName, Users.avatarKey, Users.supporterTier)
            .where { (Users.e164 eq e164) and Users.deletedAt.isNull() }
            .singleOrNull()
            ?.let { NumberOwner(it[Users.id], it[Users.displayName], it[Users.avatarKey], SupporterTier.of(it[Users.supporterTier])) }

    /** Records that [viewerId] looked at [owner]'s profile in the way [kind], if the rules allow it. */
    fun JdbcTransaction.record(
        viewerId: UUID,
        owner: NumberOwner?,
        kind: VisitKind,
        now: OffsetDateTime,
    ) {
        if (owner == null || owner.id == viewerId) return
        val viewerIncognito =
            Users
                .select(Users.incognito)
                .where { (Users.id eq viewerId) and Users.deletedAt.isNull() }
                .singleOrNull()
                ?.get(Users.incognito) ?: true // no profile row: record nothing
        if (viewerIncognito || recentlyRecorded(viewerId, owner.id, kind, now)) return
        ProfileViews.insert {
            it[viewedUserId] = owner.id
            it[viewerUserId] = viewerId
            it[ProfileViews.kind] = kind.value
            it[at] = now
        }
    }

    private fun JdbcTransaction.recentlyRecorded(
        viewerId: UUID,
        ownerId: UUID,
        kind: VisitKind,
        now: OffsetDateTime,
    ): Boolean =
        ProfileViews
            .select(ProfileViews.id)
            .where {
                (ProfileViews.viewedUserId eq ownerId) and (ProfileViews.viewerUserId eq viewerId) and
                    (ProfileViews.kind eq kind.value) and (ProfileViews.at greaterEq now.minusHours(DEDUPE_HOURS))
            }.limit(1)
            .any()

    /** `true` if [owner] approved [viewerId]'s contact request, which shows them the owner's name. */
    fun JdbcTransaction.approvedFor(
        viewerId: UUID,
        owner: NumberOwner,
    ): Boolean =
        ContactRequests
            .select(ContactRequests.id)
            .where {
                (ContactRequests.requesterId eq viewerId) and (ContactRequests.targetId eq owner.id) and
                    (ContactRequests.status eq ReviewStatus.APPROVED)
            }.limit(1)
            .any()
}
