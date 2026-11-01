package io.github.jeshiomurmu.opencaller.core.data.sync

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * The known-bad link list on the device: sorted 32-bit host hash prefixes in a small file (not secret: the server
 * publishes the same list to every app), read into memory on first use. A missing or unreadable file is an empty
 * list, so a link check never fails because of it.
 */
class LinkPrefixStore(
    private val file: File,
    private val dispatchers: DispatcherProvider,
) {
    private val lock = Mutex()

    @Volatile private var loaded: IntArray? = null

    /** True when [prefix] is on the list. */
    suspend fun contains(prefix: Int): Boolean = (loaded ?: load()).binarySearch(prefix) >= 0

    /** How many prefixes are stored. */
    suspend fun size(): Int = (loaded ?: load()).size

    /**
     * Replaces the list with [prefixes], written to a temporary file and renamed so a crash never leaves half a list.
     *
     * @throws IOException when the file can't be written.
     */
    suspend fun replace(prefixes: IntArray) {
        val sorted = prefixes.distinct().sorted().toIntArray()
        lock.withLock {
            withContext(dispatchers.io) {
                val temp = File(file.parentFile, "${file.name}.tmp")
                DataOutputStream(temp.outputStream().buffered()).use { out ->
                    out.writeInt(sorted.size)
                    sorted.forEach(out::writeInt)
                }
                if (!temp.renameTo(file)) throw IOException("Could not replace ${file.name}")
            }
            loaded = sorted
        }
    }

    private suspend fun load(): IntArray =
        lock.withLock {
            loaded ?: withContext(dispatchers.io) { read() }.also { loaded = it }
        }

    private fun read(): IntArray =
        try {
            if (!file.exists()) {
                IntArray(0)
            } else {
                DataInputStream(file.inputStream().buffered()).use { input -> IntArray(input.readInt()) { input.readInt() } }
            }
        } catch (e: EOFException) {
            Timber.w(e, "Link prefix list truncated")
            IntArray(0)
        } catch (e: IOException) {
            Timber.w(e, "Link prefix list not readable")
            IntArray(0)
        }

    /** File name in the app's no-backup directory. */
    companion object {
        /** The list file. */
        const val FILE_NAME = "link_prefixes.bin"
    }
}
