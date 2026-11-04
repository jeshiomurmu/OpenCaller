package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the Phase 11 tables (V6__voip_flash_video.sql).

/** `push_items`: what a push stands for; the push itself carries only [id]. */
object PushItems : Table("push_items") {
    val id = javaUUID("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val refId = javaUUID("ref_id")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(id)
}

/** `call_invites`: one app call from ringing to its end; deleted a day later. */
object CallInvites : Table("call_invites") {
    val id = javaUUID("id")
    val callerId = reference("caller_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val calleeId = reference("callee_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val room = text("room").uniqueIndex()
    val state = text("state")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val ringUntil = timestampWithTimeZone("ring_until")
    val answeredAt = timestampWithTimeZone("answered_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `flash_messages`: waiting for the recipient's phone to fetch them. */
object FlashMessages : Table("flash_messages") {
    val id = javaUUID("id")
    val senderId = reference("sender_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val recipientId = reference("recipient_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(id)
}

/** `video_caller_ids`: a user's clip ([videoId], public) and an upload on its way through the transcoder. */
object VideoCallerIds : Table("video_caller_ids") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val videoId = javaUUID("video_id").nullable().uniqueIndex()
    val videoKey = text("video_key").nullable()
    val videoBytes = integer("video_bytes").nullable()
    val uploadKey = text("upload_key").nullable()
    val jobState = text("job_state").default("none")
    val jobError = text("job_error").nullable()
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val hidden = bool("hidden").default(false)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(userId)
}

/** `video_reports`: one row per reporter and clip. */
object VideoReports : Table("video_reports") {
    val videoId = javaUUID("video_id")
    val reporterId = reference("reporter_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(videoId, reporterId)
}
