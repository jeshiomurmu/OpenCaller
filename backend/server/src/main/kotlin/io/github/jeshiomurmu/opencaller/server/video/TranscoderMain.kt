package io.github.jeshiomurmu.opencaller.server.video

import io.github.jeshiomurmu.opencaller.server.config.AppConfig
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.storage.S3ObjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.video.Transcoder")

/**
 * `opencaller-server transcoder`: the video caller ID worker. It needs PostgreSQL, MinIO and ffmpeg, and serves no
 * HTTP; it takes queued clips until the container stops. Errors are logged and retried after a pause.
 */
fun runTranscoder(config: AppConfig) {
    val clock = Clock.systemUTC()
    val database = ServerDatabase.connect(config.database, Dispatchers.IO)
    val worker = TranscodeWorker(database, S3ObjectStore(config.minio, clock), FfmpegTranscoder(config.voip.video, Dispatchers.IO), clock)
    Runtime.getRuntime().addShutdownHook(Thread { database.close() })
    log.info("Transcoder {} started ({}), ffmpeg: {}", config.version, config.environment, config.voip.video.ffmpegPath)
    runBlocking {
        while (true) {
            val outcome =
                try {
                    worker.runOnce()
                } catch (e: ExposedSQLException) {
                    log.warn("Transcoder: database error", e)
                    TranscodeOutcome.IDLE
                } catch (e: IOException) {
                    log.warn("Transcoder: I/O error", e)
                    TranscodeOutcome.IDLE
                }
            if (outcome == TranscodeOutcome.IDLE) delay(config.voip.video.pollSeconds.seconds)
        }
    }
}
