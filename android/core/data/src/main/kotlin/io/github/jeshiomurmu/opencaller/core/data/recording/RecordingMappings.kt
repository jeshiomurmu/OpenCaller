package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.data.profile.millisOf
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSession
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording
import io.github.jeshiomurmu.opencaller.core.model.ServerRecording
import io.github.jeshiomurmu.opencaller.core.model.TranscriptState
import io.github.jeshiomurmu.opencaller.core.network.RecordingDto
import io.github.jeshiomurmu.opencaller.core.network.RecordingLineDto
import io.github.jeshiomurmu.opencaller.core.network.RecordingSessionDto

/** The line, or `null` when the server sent something that isn't a number and an 8-digit code. */
internal fun RecordingLineDto.toModel(): RecordingLine? =
    if (PhoneNumber.isE164(number) && CODE.matches(code)) {
        RecordingLine(sessionId, PhoneNumber(number), code, millisOf(expiresAt))
    } else {
        null
    }

internal fun RecordingSessionDto.toModel(): RecordingSession =
    RecordingSession(
        state = RecordingSessionState.entries.firstOrNull { it.name.equals(state, ignoreCase = true) } ?: RecordingSessionState.FAILED,
        recordingId = recordingId,
    )

internal fun RecordingDto.toModel(): ServerRecording =
    ServerRecording(
        id = id,
        createdAtMillis = millisOf(createdAt),
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        transcript = TranscriptState.entries.firstOrNull { it.name.equals(transcript, ignoreCase = true) } ?: TranscriptState.OFF,
        downloaded = downloaded,
        deleteByMillis = millisOf(deleteBy),
    )

internal fun RecordingEntity.toModel(): SavedRecording =
    SavedRecording(
        id = id,
        otherParty = e164?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber),
        filePath = filePath,
        durationMs = durationMs,
        transcript = transcript,
        createdAtMillis = createdAt,
    )

private val CODE = Regex("^[0-9]{8}$")
