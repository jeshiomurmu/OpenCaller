package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.database.entity.ScreenedCallEntity
import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine
import kotlinx.coroutines.flow.Flow

/** Screened calls on the phone (the Room `screened_call` table; a map in tests). */
interface ScreenedCallStore {
    /** The newest [limit] calls, newest first; empty if the database can't be read. */
    fun recent(limit: Int): Flow<List<ScreenedCall>>

    /** Saves [call]. */
    suspend fun put(call: ScreenedCall)

    /** Forgets call [id]. */
    suspend fun remove(id: String)

    /** Forgets calls that started before [beforeMillis]. */
    suspend fun deleteOlderThan(beforeMillis: Long)
}

/** The row for [ScreenedCall]: one transcript line per text line, `C ` (caller) or `A ` (assistant) first. */
internal fun ScreenedCall.toEntity(): ScreenedCallEntity =
    ScreenedCallEntity(
        id = id,
        startedAt = startedAtMillis,
        e164 = caller?.e164,
        name = details.name,
        reason = details.reason,
        urgency = details.urgency.name,
        likelySpam = details.likelySpam,
        transcript =
            transcript.joinToString("\n") { line ->
                (if (line.speaker == Speaker.CALLER) "C " else "A ") + line.text.replace('\n', ' ')
            },
        endReason = end.name,
    )

internal fun ScreenedCallEntity.toModel(): ScreenedCall =
    ScreenedCall(
        id = id,
        startedAtMillis = startedAt,
        caller = e164?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber),
        details =
            CallerDetails(
                name = name,
                reason = reason,
                urgency = CallUrgency.entries.firstOrNull { it.name == urgency } ?: CallUrgency.NORMAL,
                likelySpam = likelySpam,
            ),
        transcript =
            transcript.lines().mapNotNull { row ->
                when {
                    row.startsWith("C ") -> TranscriptLine(Speaker.CALLER, row.drop(2))
                    row.startsWith("A ") -> TranscriptLine(Speaker.ASSISTANT, row.drop(2))
                    else -> null
                }
            },
        end = ScreeningEnd.entries.firstOrNull { it.name == endReason } ?: ScreeningEnd.UNKNOWN,
    )
