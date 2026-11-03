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

/**
 * Supporter tiers as stored in `users.supporter_tier` and shown as badges (docs/prd.md F-SO-06). Family members
 * show the supporter badge.
 */
enum class SupporterTier(
    val value: String,
) {
    NONE("none"),
    SUPPORTER("supporter"),
    GOLD("gold"),
    FAMILY("family"),
    ;

    /** The badge to show, or `null` for no badge. */
    val badge: String?
        get() =
            when (this) {
                NONE -> null
                FAMILY -> SUPPORTER.value
                else -> value
            }

    /** Lookup by stored value. */
    companion object {
        /** The tier stored as [value]; anything unknown counts as [NONE]. */
        fun of(value: String?): SupporterTier = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** What a user's supporter status unlocks (docs/prd.md F-SO-02/03, F-PR-03). */
fun interface Entitlements {
    /** `true` if [userId] has supporter features (viewers and searchers, the extended spam list). */
    suspend fun isSupporter(userId: UUID): Boolean

    /** `true` if everyone has them, signed in or not (a self-hosted server). */
    val everyone: Boolean get() = false
}

/**
 * A user is a supporter if `users.supporter_tier` names a tier (billing keeps it current for Play purchases; an
 * operator may grant one by hand), or for everyone when the server is [selfHosted].
 */
class StoredTierEntitlements(
    private val db: ServerDatabase,
    private val selfHosted: Boolean = false,
) : Entitlements {
    override val everyone: Boolean get() = selfHosted

    override suspend fun isSupporter(userId: UUID): Boolean =
        selfHosted ||
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
        "This is available to supporters.",
    )
