package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.db.CallInvites
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.voip.Recipients.factsFor
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** Reading `call_invites` rows for [VoipService]. */
internal object CallRows {
    private const val NONCE_BYTES = 12

    /** A new room for [a] and [b]: their sorted user IDs and a random nonce. */
    fun roomFor(
        a: UUID,
        b: UUID,
        random: SecureRandom,
    ): String {
        val (first, second) = listOf(a.toString(), b.toString()).sorted()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return "${first}_${second}_${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}"
    }

    /** Invite [inviteId] if [userId] is one of its parties, else 404 `call_not_found`. */
    fun JdbcTransaction.inviteOf(
        userId: UUID,
        inviteId: UUID,
    ): ResultRow =
        CallInvites
            .selectAll()
            .where { (CallInvites.id eq inviteId) and ((CallInvites.callerId eq userId) or (CallInvites.calleeId eq userId)) }
            .firstOrNull() ?: throw ApiProblem(HttpStatusCode.NotFound, "call_not_found", "No such call")

    /** [row] as [userId] sees it at [now]. */
    fun JdbcTransaction.viewOf(
        userId: UUID,
        now: OffsetDateTime,
        row: ResultRow,
    ): InviteView {
        val isCaller = row[CallInvites.callerId] == userId
        val other = factsFor(if (isCaller) row[CallInvites.calleeId] else row[CallInvites.callerId])
        return InviteView(
            inviteId = row[CallInvites.id].toString(),
            state = currentState(row, now),
            role = if (isCaller) "caller" else "callee",
            number = other?.e164.orEmpty(),
            name = other?.name,
            // Only the callee sees the caller's clip; the caller already knows whom they called.
            videoId = other?.videoId?.takeIf { !isCaller }?.toString(),
            ringUntil = row[CallInvites.ringUntil].withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString(),
        )
    }

    // A call nobody answered in time counts as missed, whether or not the row says so yet.
    fun currentState(
        row: ResultRow,
        now: OffsetDateTime,
    ): String {
        val state = row[CallInvites.state]
        return if (state == VoipService.RINGING && row[CallInvites.ringUntil].isBefore(now)) VoipService.MISSED else state
    }

    /** 409 `call_state`. */
    fun callOver(state: String) = ApiProblem(HttpStatusCode.Conflict, "call_state", "The call can't do that now", "The call is $state.")
}
