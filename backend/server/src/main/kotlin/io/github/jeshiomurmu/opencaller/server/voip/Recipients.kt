package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.VideoCallerIds
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** What a signed-in user may see about another user: their number, and name and clip as their profile allows. */
data class UserFacts(
    val e164: String,
    val name: String?,
    val videoId: UUID?,
)

/**
 * Who app calls and Flash messages can reach (ADR 0007): a signed-in user, not deleted, with reachability on (the
 * default) and at least one device that can receive pushes. "Not a user", "turned off" and "no device" all answer
 * the same 404 `not_reachable`, so the endpoint tells a caller no more than "can't be reached this way".
 */
object Recipients {
    /** The user behind [number] if app calls and Flash messages can reach them, else 404 `not_reachable`. */
    fun JdbcTransaction.reachable(
        number: E164,
        sender: UUID,
    ): UUID {
        val user =
            Users
                .select(Users.id)
                .where { (Users.e164 eq number.value) and Users.deletedAt.isNull() and (Users.reachable eq true) }
                .map { it[Users.id] }
                .firstOrNull()
        val pushable =
            user != null &&
                user != sender &&
                !Devices
                    .select(Devices.id)
                    .where { (Devices.userId eq user) and Devices.pushType.isNotNull() and Devices.pushToken.isNotNull() }
                    .empty()
        if (!pushable) throw notReachable()
        return user
    }

    /** [userId]'s number, and name and clip as their visibility allows a signed-in viewer to see them. */
    fun JdbcTransaction.factsFor(userId: UUID): UserFacts? =
        Users
            .join(VideoCallerIds, JoinType.LEFT, Users.id, VideoCallerIds.userId)
            .selectAll()
            .where { Users.id eq userId }
            .map { row ->
                // A signed-in viewer sees `everyone` and `registered_only` profiles; `nobody` shows nothing but the number.
                val visible = row[Users.deletedAt] == null && row[Users.visibility] != Visibility.NOBODY
                val clip = row.getOrNull(VideoCallerIds.videoId)?.takeIf { row.getOrNull(VideoCallerIds.hidden) == false }
                UserFacts(
                    e164 = row[Users.e164],
                    name = row[Users.displayName].takeIf { visible },
                    videoId = clip.takeIf { visible },
                )
            }.firstOrNull()

    /** 404 `not_reachable`. */
    fun notReachable() =
        ApiProblem(
            HttpStatusCode.NotFound,
            "not_reachable",
            "This number can't be reached with OpenCaller",
            "The number doesn't use OpenCaller, or doesn't take app calls and Flash messages.",
        )
}
