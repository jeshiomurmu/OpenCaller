package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the Phase 13 tables (V8__recording_line.sql; ADR 0009).

/** `recording_countries`: the admin web's overrides of `RECORDING_COUNTRIES`. */
object RecordingCountries : Table("recording_countries") {
    val countryIso = text("country_iso")
    val enabled = bool("enabled")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(countryIso)
}

/** `recording_sessions`: one Record tap; found by its code's HMAC ([codeHash]) while pending. */
object RecordingSessions : Table("recording_sessions") {
    val id = javaUUID("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val lineE164 = text("line_e164")
    val codeHash = text("code_hash")
    val state = text("state")
    val transcribe = bool("transcribe").default(false)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `recordings`: a finished recording in object storage, until it is deleted. */
object Recordings : Table("recordings") {
    val id = javaUUID("id")
    val sessionId = optReference("session_id", RecordingSessions.id, onDelete = ReferenceOption.SET_NULL)
    val userId = optReference("user_id", Users.id, onDelete = ReferenceOption.SET_NULL)
    val objectKey = text("object_key")
    val sizeBytes = long("size_bytes")
    val durationMs = long("duration_ms")
    val transcriptState = text("transcript_state")
    val transcript = text("transcript").nullable()
    val transcriptStartedAt = timestampWithTimeZone("transcript_started_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val downloadedAt = timestampWithTimeZone("downloaded_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
