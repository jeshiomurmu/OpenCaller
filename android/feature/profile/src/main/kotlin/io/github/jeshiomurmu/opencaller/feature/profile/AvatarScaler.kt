package io.github.jeshiomurmu.opencaller.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes a picked photo a profile picture: decoded at most [ProfileLimits.PICTURE_SIDE] pixels on its longer side
 * and saved as JPEG (tens of kilobytes, far under the server's 1 MB). Only the scaled copy leaves the phone.
 */
class AvatarScaler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : PictureScaler {
        override suspend fun scale(uri: String): AppResult<ByteArray> =
            withContext(dispatchers.default) {
                try {
                    val bitmap = decode(uri)
                    try {
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                        AppResult.Success(out.toByteArray())
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: IOException) {
                    Timber.w(e, "Profile picture not readable")
                    AppResult.Failure(AppError.Storage)
                } catch (e: SecurityException) {
                    Timber.w(e, "Profile picture not allowed")
                    AppResult.Failure(AppError.PermissionDenied)
                }
            }

        private fun decode(uri: String): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > ProfileLimits.PICTURE_SIDE) {
                    val scale = ProfileLimits.PICTURE_SIDE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        private companion object {
            const val QUALITY = 85
        }
    }
