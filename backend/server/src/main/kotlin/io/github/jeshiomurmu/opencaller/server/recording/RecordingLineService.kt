package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.business.regionOf
import io.github.jeshiomurmu.opencaller.server.db.RecordingSessions
import io.github.jeshiomurmu.opencaller.server.db.Recordings
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.Entitlements
import io.github.jeshiomurmu.opencaller.server.profile.activeUser
import io.github.jeshiomurmu.opencaller.server.profile.supporterRequired
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ACTIVE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.PENDING
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Hands out the recording line (docs/prd.md F-UT-03, ADR 0009): `POST /v1/lines/recording` gives a supporter (or
 * anyone on a self-hosted server) in an approved country their country's line and a one-time code; the recorder
 * agent claims the session with the code it reads from the call's tones. The server never learns either party's
 * number from the call: the code alone links the call to the account.
 */
class RecordingLineService(
    private val db: ServerDatabase,
    private val countries: RecordingCountryPolicy,
    private val entitlements: Entitlements,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val codes: RecordingCodes,
) {
    /** A new session for [userId]: their country's line and a fresh code, valid for [SESSION_MINUTES]. */
    suspend fun allocate(userId: UUID): RecordingLineView {
        requireValid(countries.enabled) { recordingUnavailable("This server has no recording line.") }
        requireValid(entitlements.everyone || entitlements.isSupporter(userId), ::supporterRequired)
        val (e164, transcribe) = db.query { activeUser(userId).let { it[Users.e164] to it[Users.recordingTranscripts] } }
        val country = E164.parseOrNull(e164)?.let(::regionOf)
        requireValid(country != null) { recordingUnavailable("Your number has no country.") }
        requireValid(countries.approved(checkNotNull(country))) { recordingUnavailable("Recording isn't offered in your country.") }
        val line = countries.lineIn(country)
        requireValid(line != null) { recordingUnavailable("There is no recording line for your country yet.") }
        enforce(rateLimiter.hit(SESSIONS_PER_USER, userId.toString()), "Too many recordings started")
        val now = now()
        val sessionId = UUID.randomUUID()
        val code =
            db.query {
                val code = unusedCode(now)
                RecordingSessions.insert {
                    it[id] = sessionId
                    it[RecordingSessions.userId] = userId
                    it[lineE164] = checkNotNull(line)
                    it[codeHash] = codes.hash(code)
                    it[state] = PENDING
                    it[RecordingSessions.transcribe] = transcribe
                    it[createdAt] = now
                    it[expiresAt] = now.plusMinutes(SESSION_MINUTES)
                }
                code
            }
        return RecordingLineView(sessionId.toString(), checkNotNull(line), code, now.plusMinutes(SESSION_MINUTES).toInstant().toString())
    }

    /** [userId]'s session [sessionId]: the app polls it after dialling and merges once it is `active`. */
    suspend fun session(
        userId: UUID,
        sessionId: UUID,
    ): RecordingSessionView =
        db.query {
            val row =
                RecordingSessions
                    .selectAll()
                    .where { (RecordingSessions.id eq sessionId) and (RecordingSessions.userId eq userId) }
                    .singleOrNull() ?: throw sessionNotFound()
            val recording =
                Recordings
                    .select(Recordings.id)
                    .where { Recordings.sessionId eq sessionId }
                    .singleOrNull()
                    ?.get(Recordings.id)
            val expired = row[RecordingSessions.state] == PENDING && !row[RecordingSessions.expiresAt].isAfter(now())
            RecordingSessionView(
                sessionId = sessionId.toString(),
                state = if (expired) RecordingStates.FAILED else row[RecordingSessions.state],
                recordingId = recording?.toString(),
            )
        }

    /**
     * The agent in LiveKit room [ClaimRequest.room] read [ClaimRequest.code]: the pending session with that code
     * becomes active. 404 `code_not_found` for a wrong, used or expired code (the same answer for each); at most
     * [CLAIMS_PER_ROOM] tries per call and [CLAIMS_PER_WINDOW] for the whole server.
     */
    suspend fun claim(request: ClaimRequest): ClaimResponse {
        val room = request.room.take(MAX_ROOM_LENGTH)
        enforce(rateLimiter.hit(CLAIMS_ALL, "all"), "Too many code attempts")
        if (room.isNotEmpty()) enforce(rateLimiter.hit(CLAIMS_ROOM, room), "Too many code attempts in this call")
        val code = request.code.filter(Char::isDigit).takeIf { it.length == CODE_LENGTH && it.length == request.code.length }
        val now = now()
        val sessionId =
            code?.let {
                db.query {
                    val row =
                        RecordingSessions
                            .selectAll()
                            .where {
                                (RecordingSessions.codeHash eq codes.hash(it)) and (RecordingSessions.state eq PENDING) and
                                    (RecordingSessions.expiresAt greater now)
                            }.forUpdate()
                            .singleOrNull()
                    row?.get(RecordingSessions.id)?.also { id ->
                        RecordingSessions.update({ RecordingSessions.id eq id }) {
                            it[state] = ACTIVE
                            it[claimedAt] = now
                        }
                    }
                }
            } ?: throw ApiProblem(HttpStatusCode.NotFound, "code_not_found", "No recording waits for this code")
        return ClaimResponse(sessionId.toString(), objectKey(sessionId), MAX_MINUTES)
    }

    // A code no pending session uses (the partial unique index guards the rare race between two requests).
    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.unusedCode(now: OffsetDateTime): String {
        repeat(CODE_ATTEMPTS) {
            val code = codes.next()
            val taken =
                RecordingSessions
                    .select(RecordingSessions.id)
                    .where { (RecordingSessions.codeHash eq codes.hash(code)) and (RecordingSessions.state eq PENDING) }
                    .any()
            if (!taken) return code
        }
        error("No free recording code after $CODE_ATTEMPTS attempts at $now")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** Digits in a code. */
        const val CODE_LENGTH = 8

        /** How long a code waits for the call. */
        const val SESSION_MINUTES = 10L

        /** The longest recording; the agent stops the egress after this. */
        const val MAX_MINUTES = 180

        /** Where Egress writes session [sessionId]'s file. */
        fun objectKey(sessionId: UUID): String = "recordings/$sessionId.ogg"

        private const val CODE_ATTEMPTS = 20
        private const val MAX_ROOM_LENGTH = 128
        private const val CLAIMS_PER_ROOM = 3
        private const val CLAIMS_PER_WINDOW = 600
        private val SESSIONS_PER_USER = RateLimit("recording_sessions", maxHits = 20, window = 1.hours)
        private val CLAIMS_ROOM = RateLimit("recording_claims_room", maxHits = CLAIMS_PER_ROOM, window = 1.hours)
        private val CLAIMS_ALL = RateLimit("recording_claims", maxHits = CLAIMS_PER_WINDOW, window = 10.minutes)
    }
}

/**
 * One-time codes: [CODE_LENGTH][RecordingLineService.CODE_LENGTH] random digits, kept only as an HMAC with the
 * server's pepper (8 digits would be quick to brute-force from a plain hash).
 */
class RecordingCodes(
    pepper: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), HMAC)

    /** A fresh code. */
    fun next(): String = random.nextInt(CODE_SPACE).toString().padStart(RecordingLineService.CODE_LENGTH, '0')

    /** The stored form of [code]. */
    fun hash(code: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        return HexFormat.of().formatHex(mac.doFinal(code.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val CODE_SPACE = 100_000_000
    }
}

/** 404 `session_not_found`. */
internal fun sessionNotFound() = ApiProblem(HttpStatusCode.NotFound, "session_not_found", "No such recording session")
