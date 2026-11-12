package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the AI assistant's tables (V9__assistant.sql; ADR 0010).

/** `assistant_numbers`: the operator's pool; [userId] is the user a number is assigned to. */
object AssistantNumbers : Table("assistant_numbers") {
    val e164 = text("e164")
    val countryIso = text("country_iso")
    val enabled = bool("enabled").default(true)
    val userId = optReference("user_id", Users.id, onDelete = ReferenceOption.SET_NULL).uniqueIndex()
    val assignedAt = timestampWithTimeZone("assigned_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(e164)
}

/** `assistant_calls`: a call the agent answered (user and room only; no phone number). */
object AssistantCalls : Table("assistant_calls") {
    val id = javaUUID("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val room = text("room")
    val startedAt = timestampWithTimeZone("started_at").defaultExpression(CurrentTimestampWithTimeZone)
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `assistant_usage`: seconds used per user and calendar month (UTC; [month] is the month's first day). */
object AssistantUsage : Table("assistant_usage") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val month = date("month")
    val seconds = integer("seconds").default(0)
    override val primaryKey = PrimaryKey(userId, month)
}
