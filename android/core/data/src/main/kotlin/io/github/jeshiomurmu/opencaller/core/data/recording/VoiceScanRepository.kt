package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.model.ScamCue
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanResult
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanSession
import io.github.jeshiomurmu.opencaller.core.network.ScanSessionDto
import io.github.jeshiomurmu.opencaller.core.network.VoiceScanApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import javax.inject.Inject

/** The voice scan as the scanner flow uses it ([VoiceScanRepository]; a fake in tests). */
interface VoiceScans {
    /** The recording line and a one-time code for a voice scan. */
    suspend fun startScan(): AppResult<RecordingLine>

    /** Scan [sessionId]'s state and result. */
    suspend fun scan(sessionId: String): AppResult<VoiceScanSession>
}

/**
 * The voice scan on the recording line (docs/prd.md F-PR-13, ADR 0011). Nothing is stored on the phone or sent
 * besides the session: the server keeps the outcome (a probability and cue ids) for a day.
 */
class VoiceScanRepository
    @Inject
    constructor(
        private val api: VoiceScanApi,
    ) : VoiceScans {
        override suspend fun startScan(): AppResult<RecordingLine> =
            apiCall { api.start() }.flatMap { dto ->
                dto.toModel()?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Validation(INVALID_LINE))
            }

        override suspend fun scan(sessionId: String): AppResult<VoiceScanSession> =
            if (ID.matches(sessionId)) apiCall { api.scan(sessionId) }.map { it.toModel() } else AppResult.Failure(AppError.NotFound)

        private companion object {
            const val INVALID_LINE = "invalid_line"
            val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        }
    }

/** An ended scan without a valid probability reads as failed; unknown cue ids (a newer server) are skipped. */
internal fun ScanSessionDto.toModel(): VoiceScanSession {
    val parsed = RecordingSessionState.entries.firstOrNull { it.name.equals(state, ignoreCase = true) } ?: RecordingSessionState.FAILED
    val probability = spoofProbability?.takeIf { it in 0f..1f }
    return when {
        parsed != RecordingSessionState.ENDED -> VoiceScanSession(parsed)
        probability == null -> VoiceScanSession(RecordingSessionState.FAILED)
        else -> VoiceScanSession(parsed, VoiceScanResult(probability, cues.mapNotNull(ScamCue::of).distinct()))
    }
}
