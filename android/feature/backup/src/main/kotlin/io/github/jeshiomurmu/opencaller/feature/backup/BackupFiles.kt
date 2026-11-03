package io.github.jeshiomurmu.opencaller.feature.backup

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** Reads and writes a backup file the user picked (Storage Access Framework). */
interface BackupFiles {
    /** Writes [bytes] to [uri]. */
    suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): AppResult<Unit>

    /** Reads [uri] (at most [MAX_BYTES]). */
    suspend fun read(uri: String): AppResult<ByteArray>

    /** Limits. */
    companion object {
        /** No backup is anywhere near this; anything larger isn't one. */
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}

/** [BackupFiles] through the content resolver. */
class ResolverBackupFiles
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : BackupFiles {
        override suspend fun write(
            uri: String,
            bytes: ByteArray,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                guarded("written") {
                    (
                        context.contentResolver.openOutputStream(
                            uri.toUri(),
                            "wt",
                        ) ?: throw IOException("no output stream")
                    ).use { it.write(bytes) }
                }
            }

        override suspend fun read(uri: String): AppResult<ByteArray> =
            withContext(dispatchers.io) {
                guarded("read") {
                    (context.contentResolver.openInputStream(uri.toUri()) ?: throw IOException("no input stream")).use(::readBounded)
                }
            }

        // At most MAX_BYTES (InputStream.readNBytes needs API 33).
        private fun readBounded(stream: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > BackupFiles.MAX_BYTES) throw IOException("file too large")
            }
            return out.toByteArray()
        }

        private inline fun <T> guarded(
            what: String,
            block: () -> T,
        ): AppResult<T> =
            try {
                AppResult.Success(block())
            } catch (e: IOException) {
                Timber.w(e, "Backup file not %s", what)
                AppResult.Failure(AppError.Storage)
            } catch (e: SecurityException) {
                Timber.w(e, "Backup file not %s (no access)", what)
                AppResult.Failure(AppError.PermissionDenied)
            }

        private companion object {
            const val BUFFER_BYTES = 64 * 1024
        }
    }
