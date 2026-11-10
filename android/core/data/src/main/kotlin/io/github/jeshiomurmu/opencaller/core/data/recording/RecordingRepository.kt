package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSession
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording
import io.github.jeshiomurmu.opencaller.core.model.TranscriptState
import io.github.jeshiomurmu.opencaller.core.network.RecordingApi
import io.github.jeshiomurmu.opencaller.core.network.RecordingDownloadDto
import io.github.jeshiomurmu.opencaller.core.network.RecordingSettingsDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Recordings as the recorder and the screens use them ([RecordingRepository]; a fake in tests). */
@Suppress("TooManyFunctions") // One function per thing the screens and the recorder do with recordings.
interface CallRecordings {
    /** Recordings saved on this phone, newest first. */
    val saved: Flow<List<SavedRecording>>

    /** The recording line and a one-time code for a new recording. */
    suspend fun startLine(): AppResult<RecordingLine>

    /** Session [sessionId]'s state. */
    suspend fun session(sessionId: String): AppResult<RecordingSession>

    /** Downloads recording [recordingId] (labelled with [otherParty]) and tells the server it is saved. */
    suspend fun save(
        recordingId: String,
        otherParty: PhoneNumber?,
    ): AppResult<SavedRecording>

    /** Saves every recording still on the server and picks up finished transcripts; returns how many were new. */
    suspend fun syncAll(): AppResult<Int>

    /** Deletes recording [id] from this phone (and from the server, if it is still there). */
    suspend fun delete(id: String): AppResult<Unit>

    /** Whether new recordings get a transcript. */
    suspend fun transcripts(): AppResult<Boolean>

    /** Turns transcripts on or off for new recordings. */
    suspend fun setTranscripts(enabled: Boolean): AppResult<Boolean>
}

/**
 * Recordings (docs/prd.md F-UT-03, ADR 0009). The server keeps a recording only until the phone has saved it
 * (deleted within an hour of [save]'s confirmation, 24 hours at the latest); the file on this phone, in the app's
 * private files, is the only lasting copy. Nothing here sends a phone number: the label stays on the phone.
 */
@Suppress("TooManyFunctions") // CallRecordings' functions plus the download steps.
class RecordingRepository(
    private val api: RecordingApi,
    private val store: RecordingStore,
    private val folder: File,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : CallRecordings {
    private val lock = Mutex()

    override val saved: Flow<List<SavedRecording>> = store.all.map { rows -> rows.map(RecordingEntity::toModel) }

    override suspend fun startLine(): AppResult<RecordingLine> =
        apiCall { api.start() }.flatMap { dto ->
            dto.toModel()?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Validation(INVALID_LINE))
        }

    override suspend fun session(sessionId: String): AppResult<RecordingSession> =
        if (ID.matches(sessionId)) apiCall { api.session(sessionId) }.map { it.toModel() } else AppResult.Failure(AppError.NotFound)

    override suspend fun save(
        recordingId: String,
        otherParty: PhoneNumber?,
    ): AppResult<SavedRecording> {
        if (!ID.matches(recordingId)) return AppResult.Failure(AppError.NotFound)
        return lock.withLock { download(recordingId, otherParty) }
    }

    override suspend fun syncAll(): AppResult<Int> =
        apiCall { api.recordings() }.map { list ->
            var added = 0
            list.map { it.toModel() }.forEach { recording ->
                val local = store.find(recording.id)
                when {
                    local == null -> if (save(recording.id, null) is AppResult.Success) added++
                    local.transcript == null && recording.transcript == TranscriptState.DONE -> fetchTranscript(recording.id)
                }
            }
            added
        }

    override suspend fun delete(id: String): AppResult<Unit> {
        val local = store.find(id)
        local?.let { withContext(dispatchers.io) { File(it.filePath).delete() } }
        store.remove(id)
        // Gone from the server already (the usual case): nothing more to do.
        return when (val result = apiCallNoContent { api.delete(id) }) {
            is AppResult.Failure -> if (result.error == AppError.NotFound || local != null) AppResult.Success(Unit) else result
            is AppResult.Success -> result
        }
    }

    override suspend fun transcripts(): AppResult<Boolean> = apiCall { api.settings() }.map { it.transcripts }

    override suspend fun setTranscripts(enabled: Boolean): AppResult<Boolean> =
        apiCall { api.updateSettings(RecordingSettingsDto(enabled)) }.map { it.transcripts }

    private suspend fun download(
        recordingId: String,
        otherParty: PhoneNumber?,
    ): AppResult<SavedRecording> =
        store.find(recordingId)?.let { existing ->
            AppResult.Success(existing.copy(e164 = existing.e164 ?: otherParty?.e164).also { store.put(it) }.toModel())
        } ?: apiCall { api.download(recordingId) }.flatMap { link -> keep(recordingId, otherParty, link) }

    // Downloads the file, then records it; the server is told only once both worked.
    private suspend fun keep(
        recordingId: String,
        otherParty: PhoneNumber?,
        link: RecordingDownloadDto,
    ): AppResult<SavedRecording> {
        val file = File(folder, "$recordingId.ogg")
        val duration = (apiCall { api.recordings() } as? AppResult.Success)?.value?.firstOrNull { it.id == recordingId }?.durationMs ?: 0
        val entity =
            RecordingEntity(
                id = recordingId,
                e164 = otherParty?.e164,
                filePath = file.absolutePath,
                durationMs = duration,
                transcript = link.transcript,
                summary = null,
                createdAt = clock.nowMillis(),
            )
        return when {
            !fetch(link.url, file) -> {
                AppResult.Failure(AppError.Network)
            }

            !store.put(entity) -> {
                // Without a row the file would be lost to the user; keep the server copy (no confirmation), retry later.
                file.delete()
                AppResult.Failure(AppError.Storage)
            }

            else -> {
                // The server deletes its copy soon after this; a failed confirmation only means it waits for the 24 h limit.
                val confirmed = apiCallNoContent { api.downloaded(recordingId) }
                if (confirmed is AppResult.Failure) Timber.i("Download not confirmed: %s", confirmed.error)
                AppResult.Success(entity.toModel())
            }
        }
    }

    private suspend fun fetchTranscript(recordingId: String) {
        val text = (apiCall { api.download(recordingId) } as? AppResult.Success)?.value?.transcript ?: return
        store.find(recordingId)?.let { store.put(it.copy(transcript = text)) }
    }

    private suspend fun fetch(
        url: String,
        file: File,
    ): Boolean {
        val body =
            try {
                api.file(url).let { response -> response.body()?.takeIf { response.isSuccessful } }
            } catch (e: IOException) {
                Timber.i(e, "Recording not downloaded")
                null
            }
        return body != null && withContext(dispatchers.io) { write(body, file) }
    }

    private fun write(
        body: ResponseBody,
        file: File,
    ): Boolean {
        folder.mkdirs()
        val part = File(folder, "${file.name}.part")
        val saved = body.use { copy(it.byteStream(), part) } && part.renameTo(file)
        if (!saved) part.delete()
        return saved
    }

    private fun copy(
        input: InputStream,
        part: File,
    ): Boolean =
        try {
            input.use { source -> part.outputStream().use { target -> copyLimited(source, target) } }
            true
        } catch (e: IOException) {
            Timber.w(e, "Recording download failed")
            false
        }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_BYTES) throw IOException("Recording larger than $MAX_BYTES bytes")
            output.write(buffer, 0, read)
        }
    }

    /** Limits and reasons. */
    companion object {
        /** [AppError.Validation] reason: the server's answer wasn't a usable line. */
        const val INVALID_LINE = "invalid_line"

        /** The folder under the app's files that holds recordings. */
        const val FOLDER = "recordings"

        // About 4.5 hours of Opus at 48 kHz stereo; the server stops recording after 3 hours.
        private const val MAX_BYTES = 512L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
