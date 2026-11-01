package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsImage
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes pictures fit an MMS: each one is decoded at most [MAX_SIDE] pixels on its longer side and saved as JPEG,
 * with lower quality and then a smaller size until all of them fit the carrier's message size.
 */
class ImageScaler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) {
        /** [uris] as JPEG images that together stay under [maxMessageBytes]. */
        suspend fun fit(
            uris: List<Uri>,
            maxMessageBytes: Int,
        ): AppResult<List<MmsImage>> =
            withContext(dispatchers.default) {
                if (uris.isEmpty()) return@withContext AppResult.Success(emptyList())
                val budget = ((maxMessageBytes - OVERHEAD_BYTES) / uris.size).coerceAtLeast(MIN_IMAGE_BYTES)
                try {
                    AppResult.Success(uris.map { MmsImage(JPEG, scaled(it, budget)) })
                } catch (e: IOException) {
                    Timber.w(e, "Picture not readable")
                    AppResult.Failure(AppError.Storage)
                } catch (e: SecurityException) {
                    Timber.w(e, "Picture not allowed")
                    AppResult.Failure(AppError.PermissionDenied)
                }
            }

        private fun scaled(
            uri: Uri,
            budget: Int,
        ): ByteArray {
            var side = MAX_SIDE
            while (true) {
                fitting(decode(uri, side), budget)?.let { return it }
                if (side <= MIN_SIDE) throw IOException("picture can't be made small enough")
                side = (side * SHRINK).roundToInt()
            }
        }

        // [bitmap] as JPEG at the best quality that fits [budget], or null; the bitmap is released either way.
        private fun fitting(
            bitmap: Bitmap,
            budget: Int,
        ): ByteArray? =
            try {
                QUALITIES.asSequence().map { jpeg(bitmap, it) }.firstOrNull { it.size <= budget }
            } finally {
                bitmap.recycle()
            }

        private fun decode(
            uri: Uri,
            side: Int,
        ): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > side) {
                    val scale = side.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        private fun jpeg(
            bitmap: Bitmap,
            quality: Int,
        ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        private companion object {
            const val JPEG = "image/jpeg"
            const val MAX_SIDE = 1600
            const val MIN_SIDE = 160
            const val SHRINK = 0.7f
            const val OVERHEAD_BYTES = 8 * 1024
            const val MIN_IMAGE_BYTES = 16 * 1024
            val QUALITIES = listOf(85, 70, 55, 40)
        }
    }
