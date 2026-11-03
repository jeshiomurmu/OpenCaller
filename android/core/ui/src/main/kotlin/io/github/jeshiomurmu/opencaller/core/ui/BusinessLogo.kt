package io.github.jeshiomurmu.opencaller.core.ui

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A verified business's logo in the avatar circle. [path] is the logo's file on the phone (the directory update
 * stored it; nothing is fetched here); until it is decoded, or if it can't be, nothing is drawn.
 */
@Composable
fun BusinessLogo(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    // Decoding reads a small file; it runs off the main thread so a ringing call's card never waits for it.
    val logo by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { withContext(Dispatchers.IO) { decodeLogo(ImageDecoder.createSource(File(it))) } }
    }
    logo?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(OpenCallerSpacing.avatar).clip(CircleShape),
        )
    }
}

/** [bytes] (a PNG or JPEG logo) as an image at most [LOGO_SIDE] pixels a side, or `null` if it isn't an image. */
fun decodeLogo(bytes: ByteArray): ImageBitmap? = decodeLogo(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

// A small file can still declare a huge image, so the decoder is told to scale down to LOGO_SIDE before decoding.
private fun decodeLogo(source: ImageDecoder.Source): ImageBitmap? =
    try {
        ImageDecoder
            .decodeBitmap(source) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > LOGO_SIDE) {
                    val scale = LOGO_SIDE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }.asImageBitmap()
    } catch (e: IOException) {
        Timber.w(e, "Logo not readable")
        null
    }

/** Largest side of a decoded logo, in pixels (a 40 dp avatar on a high-density screen, with room to spare). */
private const val LOGO_SIDE = 256
