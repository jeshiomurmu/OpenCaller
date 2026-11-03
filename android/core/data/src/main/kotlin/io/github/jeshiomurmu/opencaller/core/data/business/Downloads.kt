package io.github.jeshiomurmu.opencaller.core.data.business

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import okhttp3.ResponseBody
import okio.Buffer
import timber.log.Timber
import java.io.IOException

/**
 * Reads a downloaded body of at most [limit] bytes; a larger one is `null` (read no further than one byte past the
 * limit, whatever the server says its length is). A broken download is [AppError.Network].
 */
internal fun AppResult<ResponseBody>.readLimited(limit: Long): AppResult<ByteArray?> =
    when (this) {
        is AppResult.Success -> {
            try {
                AppResult.Success(value.use { readAtMost(it, limit) })
            } catch (e: IOException) {
                Timber.w(e, "Download failed")
                AppResult.Failure(AppError.Network)
            }
        }

        is AppResult.Failure -> {
            this
        }
    }

private fun readAtMost(
    body: ResponseBody,
    limit: Long,
): ByteArray? {
    val buffer = Buffer()
    val source = body.source()
    while (buffer.size <= limit && source.read(buffer, CHUNK) != -1L) {
        // Reading into the buffer.
    }
    return if (buffer.size > limit) null else buffer.readByteArray()
}

private const val CHUNK = 8_192L
