package io.github.jeshiomurmu.opencaller.server.video

import io.github.jeshiomurmu.opencaller.server.config.VideoConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Transcoding failed: the input isn't a usable video, or ffmpeg failed or took too long. */
class TranscodeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Turns an uploaded clip into the published one. */
fun interface Transcoder {
    /** Writes the published form of [input] to [output]. */
    suspend fun transcode(
        input: Path,
        output: Path,
    )
}

/**
 * ffmpeg as a separate process (the `transcoder` container): at most the first ten seconds of the first video
 * stream, no audio, subtitles, data or metadata, at most 480 px wide at 24 fps, H.264 (CRF 30, yuv420p) in an MP4
 * with the index up front for streaming. Only local files may be read (`-protocol_whitelist file`), the input is
 * parsed as MP4/MOV only, and a run is killed after [VideoConfig.timeoutSeconds].
 */
class FfmpegTranscoder(
    private val config: VideoConfig,
    private val io: CoroutineDispatcher,
) : Transcoder {
    override suspend fun transcode(
        input: Path,
        output: Path,
    ) = withContext(io) {
        // ffmpeg's messages go to a file, so a stuck run can't block on a full pipe and the timeout always applies.
        val log = Files.createTempFile("ffmpeg", ".log")
        try {
            val process =
                try {
                    ProcessBuilder(command(input, output)).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                } catch (e: IOException) {
                    throw TranscodeException("ffmpeg could not be started", e)
                }
            if (!process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw TranscodeException("ffmpeg took longer than ${config.timeoutSeconds} s")
            }
            if (process.exitValue() != 0) {
                val last = Files.readAllLines(log).lastOrNull { it.isNotBlank() }?.take(LOG_TAIL)
                throw TranscodeException("ffmpeg failed (exit ${process.exitValue()}): $last")
            }
        } finally {
            Files.deleteIfExists(log)
        }
    }

    /** The ffmpeg command line. */
    fun command(
        input: Path,
        output: Path,
    ): List<String> =
        listOf(
            config.ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error",
            "-protocol_whitelist", "file", "-f", "mov", "-i", input.toString(),
            "-t", MAX_SECONDS, "-map", "0:v:0", "-an", "-sn", "-dn", "-map_metadata", "-1",
            "-vf", "scale='min($MAX_WIDTH,iw)':-2,fps=$FPS",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", CRF, "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", "-f", "mp4", "-y", output.toString(),
        )

    private companion object {
        const val MAX_SECONDS = "10"
        const val MAX_WIDTH = 480
        const val FPS = 24
        const val CRF = "30"
        const val LOG_TAIL = 300
    }
}
