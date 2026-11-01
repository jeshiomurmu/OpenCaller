package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/** Supporter tiers as stored in `users.supporter_tier` and shown as badges (docs/prd.md F-SO-06). */
enum class SupporterTier(
    val value: String,
) {
    NONE("none"),
    SUPPORTER("supporter"),
    GOLD("gold"),
    ;

    /** The badge to show, or `null` for no badge. */
    val badge: String? get() = value.takeUnless { this == NONE }

    /** Lookup by stored value. */
    companion object {
        /** The tier stored as [value]; anything unknown counts as [NONE]. */
        fun of(value: String?): SupporterTier = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** What a user's supporter status unlocks (docs/prd.md F-SO-02/03). */
fun interface Entitlements {
    /** `true` if [userId] may see who viewed or searched for them. */
    suspend fun isSupporter(userId: UUID): Boolean
}

/**
 * Stub until billing exists (Phase 10, `POST /v1/billing/verify`): a user is a supporter if `users.supporter_tier`
 * says so. Nothing sets that column yet, so in production nobody is a supporter; tests and an operator can set it.
 */
class StoredTierEntitlements(
    private val db: ServerDatabase,
) : Entitlements {
    override suspend fun isSupporter(userId: UUID): Boolean =
        db.query {
            Users
                .select(Users.supporterTier)
                .where { (Users.id eq userId) and Users.deletedAt.isNull() }
                .singleOrNull()
                ?.let { SupporterTier.of(it[Users.supporterTier]) != SupporterTier.NONE } ?: false
        }
}

/** 403 `supporter_required`. */
fun supporterRequired() =
    ApiProblem(
        HttpStatusCode.Forbidden,
        "supporter_required",
        "This is a supporter feature",
        "Who viewed or searched for you is available to supporters.",
    )
