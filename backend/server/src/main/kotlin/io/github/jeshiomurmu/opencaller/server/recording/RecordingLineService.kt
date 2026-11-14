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
    /**
     * A new session of [kind] (`record`, or `scan` for the voice scanner, ADR 0011) for [userId]: their country's line
     * and a fresh code, valid for [SESSION_MINUTES]. A scan needs the scanner switched on and follows the recording
     * line's legal switch (the owner's decision of 2026-11-14).
     */
    suspend fun allocate(
        userId: UUID,
        kind: String = RecordingKinds.RECORD,
    ): RecordingLineView {
        val scan = kind == RecordingKinds.SCAN
        val unavailable = { detail: String -> if (scan) scannerUnavailable(detail) else recordingUnavailable(detail) }
        requireValid(countries.enabled) { unavailable("This server has no recording line.") }
        requireValid(!scan || countries.scannerEnabled) { unavailable("This server has no voice scanner.") }
        requireValid(entitlements.everyone || entitlements.isSupporter(userId), ::supporterRequired)
        val (e164, transcribe) = db.query { activeUser(userId).let { it[Users.e164] to it[Users.recordingTranscripts] } }
        val country = E164.parseOrNull(e164)?.let(::regionOf)
        requireValid(country != null) { unavailable("Your number has no country.") }
        requireValid(countries.approved(checkNotNull(country))) { unavailable("It isn't offered in your country.") }
        val line = countries.lineIn(country)
        requireValid(line != null) { unavailable("There is no line for your country yet.") }
        enforce(rateLimiter.hit(if (scan) SCANS_PER_USER else SESSIONS_PER_USER, userId.toString()), "Too many sessions started")
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
                    it[RecordingSessions.transcribe] = transcribe && !scan
                    it[RecordingSessions.kind] = kind
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
                    .where {
                        (RecordingSessions.id eq sessionId) and (RecordingSessions.userId eq userId) and
                            (RecordingSessions.kind eq RecordingKinds.RECORD)
                    }.singleOrNull() ?: throw sessionNotFound()
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
     * The agent of [ClaimRequest.kind] in LiveKit room [ClaimRequest.room] read [ClaimRequest.code]: the pending session
     * with that code becomes active. 404 `code_not_found` for a wrong, used or expired code (the same answer for each);
     * 409 `other_kind` when the code belongs to a session of the other kind, which stays pending for the other agent
     * (both agents join every call on the line, ADR 0011). At most [CLAIMS_PER_ROOM] tries per call and agent, and
     * [CLAIMS_PER_WINDOW] for the whole server.
     */
    suspend fun claim(request: ClaimRequest): ClaimResponse {
        val room = request.room.take(MAX_ROOM_LENGTH)
        val kind = request.kind
        requireValid(kind in RecordingKinds.ALL) { invalidRecording("kind must be record or scan.") }
        enforce(rateLimiter.hit(CLAIMS_ALL, "all"), "Too many code attempts")
        if (room.isNotEmpty()) enforce(rateLimiter.hit(CLAIMS_ROOM, "$room:$kind"), "Too many code attempts in this call")
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
                    if (row != null && row[RecordingSessions.kind] != kind) throw otherKind()
                    row?.get(RecordingSessions.id)?.also { id ->
                        RecordingSessions.update({ RecordingSessions.id eq id }) {
                            it[state] = ACTIVE
                            it[claimedAt] = now
                        }
                    }
                }
            } ?: throw ApiProblem(HttpStatusCode.NotFound, "code_not_found", "No recording waits for this code")
        return if (kind == RecordingKinds.SCAN) {
            ClaimResponse(sessionId.toString(), null, SCAN_MAX_MINUTES, kind)
        } else {
            ClaimResponse(sessionId.toString(), objectKey(sessionId), MAX_MINUTES, kind)
        }
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

        /** The longest scan; the scanner listens for a few seconds and hangs up long before. */
        const val SCAN_MAX_MINUTES = 2

        /** Where Egress writes session [sessionId]'s file. */
        fun objectKey(sessionId: UUID): String = "recordings/$sessionId.ogg"

        private const val CODE_ATTEMPTS = 20
        private const val MAX_ROOM_LENGTH = 128
        private const val CLAIMS_PER_ROOM = 3
        private const val CLAIMS_PER_WINDOW = 600
        private val SESSIONS_PER_USER = RateLimit("recording_sessions", maxHits = 20, window = 1.hours)
        private val SCANS_PER_USER = RateLimit("scan_sessions", maxHits = 20, window = 1.hours)
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

/** 409 `other_kind`: the code is the other agent's (a scan for the recorder, or the reverse); it stays pending. */
private fun otherKind() = ApiProblem(HttpStatusCode.Conflict, "other_kind", "This code belongs to the other agent")

/** 404 `session_not_found`. */
internal fun sessionNotFound() = ApiProblem(HttpStatusCode.NotFound, "session_not_found", "No such recording session")
