package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** The Ghost Call caller's photo: one scaled JPEG in the app's private files, never uploaded. */
interface GhostCallPhotos {
    /** Copies the picked image [uri], scaled to at most [SIDE] pixels on its longer side. */
    suspend fun save(uri: String): AppResult<Unit>

    /** Removes the photo. */
    suspend fun delete()

    /** The photo, or `null`. */
    suspend fun load(): Bitmap?

    /** Sizes. */
    companion object {
        /** Longest side of the stored photo. */
        const val SIDE = 512
    }
}

/** [GhostCallPhotos] in `files/ghost_call/photo.jpg`. */
class FileGhostCallPhotos
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : GhostCallPhotos {
        private val file: File get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

        override suspend fun save(uri: String): AppResult<Unit> =
            withContext(dispatchers.io) {
                try {
                    val bitmap = decode(uri)
                    try {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                        AppResult.Success(Unit)
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: IOException) {
                    Timber.w(e, "Ghost Call photo not readable")
                    AppResult.Failure(AppError.Storage)
                } catch (e: SecurityException) {
                    Timber.w(e, "Ghost Call photo not allowed")
                    AppResult.Failure(AppError.PermissionDenied)
                }
            }

        override suspend fun delete() {
            withContext(dispatchers.io) { if (file.exists() && !file.delete()) Timber.w("Ghost Call photo not deleted") }
        }

        override suspend fun load(): Bitmap? =
            withContext(dispatchers.io) {
                file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            }

        private fun decode(uri: String): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > GhostCallPhotos.SIDE) {
                    val scale = GhostCallPhotos.SIDE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        private companion object {
            const val DIRECTORY = "ghost_call"
            const val FILE_NAME = "photo.jpg"
            const val QUALITY = 85
        }
    }
