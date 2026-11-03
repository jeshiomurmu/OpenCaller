package io.github.jeshiomurmu.opencaller.feature.business

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaimRepository
import io.github.jeshiomurmu.opencaller.core.data.business.ClaimFile
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [ClaimFileReader] over the content resolver. Documents are uploaded as picked (the reviewer needs them as they
 * are); a logo is decoded at most [LOGO_SIDE] pixels a side and saved as PNG, or JPEG when the PNG is too large.
 * Too-large and wrong-type files fail as `AppError.Validation` before anything is sent.
 */
class DeviceClaimFiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : ClaimFileReader {
        override suspend fun document(uri: String): AppResult<PickedFile> =
            withContext(dispatchers.io) {
                reading {
                    val type = context.contentResolver.getType(uri.toUri())
                    if (type == null || type !in DOCUMENT_TYPES) {
                        AppResult.Failure(AppError.Validation(FILE_TYPE_ERROR))
                    } else {
                        val limit = ClaimFileKind.MAX_DOCUMENT_BYTES
                        val bytes = context.contentResolver.openInputStream(uri.toUri())?.use { it.readAtMost(limit) }
                        when {
                            bytes == null -> AppResult.Failure(AppError.Storage)
                            bytes.size > limit -> AppResult.Failure(AppError.Validation(BusinessClaimRepository.FILE_TOO_LARGE))
                            else -> AppResult.Success(PickedFile(displayName(uri), ClaimFile(bytes, type)))
                        }
                    }
                }
            }

        override suspend fun logo(uri: String): AppResult<PickedFile> =
            withContext(dispatchers.default) {
                reading {
                    val bitmap = decode(uri)
                    try {
                        val png = bitmap.encode(Bitmap.CompressFormat.PNG, PNG_TYPE)
                        val logo =
                            png.takeIf { it.bytes.size <= ClaimFileKind.MAX_LOGO_BYTES }
                                ?: bitmap.encode(Bitmap.CompressFormat.JPEG, JPEG_TYPE)
                        if (logo.bytes.size > ClaimFileKind.MAX_LOGO_BYTES) {
                            AppResult.Failure(AppError.Validation(BusinessClaimRepository.FILE_TOO_LARGE))
                        } else {
                            AppResult.Success(PickedFile(displayName(uri), logo))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

        // An unreadable file is a storage failure; a revoked grant is a permission failure. Both are logged.
        private inline fun reading(block: () -> AppResult<PickedFile>): AppResult<PickedFile> =
            try {
                block()
            } catch (e: IOException) {
                Timber.w(e, "Claim file not readable")
                AppResult.Failure(AppError.Storage)
            } catch (e: SecurityException) {
                Timber.w(e, "Claim file not allowed")
                AppResult.Failure(AppError.PermissionDenied)
            }

        private fun decode(uri: String): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > LOGO_SIDE) {
                    val scale = LOGO_SIDE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        private fun Bitmap.encode(
            format: Bitmap.CompressFormat,
            type: String,
        ): ClaimFile {
            val out = ByteArrayOutputStream()
            compress(format, QUALITY, out)
            return ClaimFile(out.toByteArray(), type)
        }

        // The name the picker shows; never logged.
        private fun displayName(uri: String): String =
            context.contentResolver
                .query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.substringAfterLast('/')

        private companion object {
            const val LOGO_SIDE = 512
            const val QUALITY = 85
            const val PNG_TYPE = "image/png"
            const val JPEG_TYPE = "image/jpeg"
            val DOCUMENT_TYPES = setOf("application/pdf", JPEG_TYPE, PNG_TYPE)
        }
    }

/** Reads at most [limit] + 1 bytes, so a larger stream is recognised without reading all of it. */
internal fun InputStream.readAtMost(limit: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var total = 0L
    while (total <= limit) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

private const val BUFFER = 8_192
