package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.VideoLinksRequestDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Starts clip downloads for the clip ids of a lookup bucket; [NONE] downloads nothing. */
fun interface ClipPrefetch {
    /** Downloads [videoIds] in the background when the network allows it. */
    fun prefetch(videoIds: Collection<String>)

    /** No downloads (tests, builds without a server). */
    companion object {
        /** Downloads nothing. */
        val NONE = ClipPrefetch { }
    }
}

/** The downloaded video caller ID of a number's owner, for the in-call screens. */
fun interface CallerClips {
    /** The clip of [number]'s owner, if a lookup named one and it is downloaded. */
    suspend fun clipFor(number: PhoneNumber): File?

    /** No clips (tests, previews). */
    companion object {
        /** Never a clip. */
        val NONE = CallerClips { null }
    }
}

/** Whether the phone is on a network clips may download over now. */
fun interface ClipNetwork {
    /** True on an unmetered network (Wi-Fi), or on any network when [mobileDataAllowed]. */
    fun allows(mobileDataAllowed: Boolean): Boolean
}

/**
 * Video caller IDs on this phone (ADR 0007). A lookup bucket names the clips of all its entries; the phone downloads
 * every one of them (so the server can't tell which number rang), only on Wi-Fi unless the user allows mobile data,
 * into a small cache. The in-call screen then plays the caller's clip, muted, if it is here.
 */
class VideoClips(
    private val dir: File,
    private val api: VoipApi,
    private val preferences: VoipPreferences,
    private val network: ClipNetwork,
    private val clock: Clock,
    access: DatabaseAccess,
    private val dispatchers: DispatcherProvider,
) : ClipPrefetch,
    CallerClips {
    private val storage = Storage(access, dispatchers)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, e -> Timber.w(e, "Clip download failed") })
    private val lock = Mutex()

    /** The downloaded clip [videoId], if it is here. */
    fun clip(videoId: String): File? = if (isClipId(videoId)) fileOf(videoId).takeIf { it.isFile && it.length() > 0 } else null

    override suspend fun clipFor(number: PhoneNumber): File? {
        val id = storage.run { numberInfoDao().videoIdOf(number.e164, clock.nowMillis()) }.getOrNull() ?: return null
        return clip(id)
    }

    override fun prefetch(videoIds: Collection<String>) {
        val wanted = videoIds.filter(::isClipId).distinct().filter { clip(it) == null }.take(MAX_PER_BUCKET)
        if (wanted.isEmpty()) return
        scope.launch { download(wanted) }
    }

    /** Downloads [videoIds] now if the network allows it (used for an app call's caller too). */
    suspend fun download(videoIds: List<String>) {
        if (!network.allows(preferences.clipsOnMobileData.first())) return
        val links = (apiCall { api.videoLinks(VideoLinksRequestDto(videoIds)) } as? AppResult.Success)?.value?.links ?: return
        lock.withLock {
            links.filter { isClipId(it.id) && clip(it.id) == null }.forEach { fetch(it.id, it.url) }
            trim()
        }
    }

    private suspend fun fetch(
        id: String,
        url: String,
    ) {
        val response =
            try {
                api.downloadVideo(url)
            } catch (e: IOException) {
                Timber.i(e, "Clip not downloaded")
                return
            }
        val body = response.body()?.takeIf { response.isSuccessful } ?: return
        withContext(dispatchers.io) {
            dir.mkdirs()
            val part = File(dir, "$id.part")
            try {
                body.use { it.byteStream().use { input -> part.outputStream().use { output -> copyLimited(input, output) } } }
                if (!part.renameTo(fileOf(id))) part.delete()
            } catch (e: IOException) {
                Timber.i(e, "Clip not saved")
                part.delete()
            }
        }
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_CLIP_BYTES) throw IOException("clip larger than $MAX_CLIP_BYTES bytes")
            output.write(buffer, 0, read)
        }
    }

    // Oldest clips go first once the cache holds more than MAX_CLIPS.
    private fun trim() {
        val files = dir.listFiles { file -> file.name.endsWith(SUFFIX) }?.sortedByDescending(File::lastModified) ?: return
        files.drop(MAX_CLIPS).forEach(File::delete)
    }

    private fun fileOf(id: String) = File(dir, "$id$SUFFIX")

    /** Limits and checks. */
    companion object {
        private const val SUFFIX = ".mp4"
        private const val MAX_CLIPS = 200
        private const val MAX_PER_BUCKET = 64
        private const val MAX_CLIP_BYTES = 8L * 1024 * 1024
        private const val BUFFER_BYTES = 16 * 1024
        private val CLIP_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** True for a clip id as the server makes them (a lowercase UUID), the only names used for files. */
        fun isClipId(value: String): Boolean = CLIP_ID.matches(value)
    }
}
