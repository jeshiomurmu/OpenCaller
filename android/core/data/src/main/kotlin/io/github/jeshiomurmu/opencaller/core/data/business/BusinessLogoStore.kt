package io.github.jeshiomurmu.opencaller.core.data.business

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Logos of verified businesses in the app's private storage, one file per business (`<businessId>.png` or `.jpg`).
 * Logos are public (the business chose to show them), so they need no encryption; they are kept so a caller card
 * shows a logo without any request during a call.
 */
class BusinessLogoStore(
    private val dir: File,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Stores [bytes] (a checked PNG or JPEG) as business [businessId]'s logo and returns the file name.
     *
     * @throws IOException when the file can't be written.
     */
    suspend fun write(
        businessId: String,
        bytes: ByteArray,
    ): String {
        val name = fileName(businessId, bytes) ?: throw IOException("Not a logo")
        withContext(dispatchers.io) {
            // Written beside and moved into place, so a crash never leaves half a logo.
            Files.createDirectories(dir.toPath())
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return name
    }

    /** The path of stored logo [name], or `null` if it isn't there (or the name isn't one this store writes). */
    fun path(name: String?): String? =
        name
            ?.takeIf(FILE::matches)
            ?.let { File(dir, it) }
            ?.takeIf { it.isFile }
            ?.absolutePath

    /** Deletes every logo except [keep] (logos of businesses no longer in the directory). */
    suspend fun keepOnly(keep: Collection<String>) {
        withContext(dispatchers.io) {
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
    }

    /** Checks and names logos. */
    companion object {
        /** Largest logo the server stores. */
        const val MAX_BYTES = 200_000

        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val FILE = Regex("${ID.pattern}\\.(png|jpg)")
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** The file name for [bytes] as business [businessId]'s logo, or `null` if the id or the image isn't valid. */
        fun fileName(
            businessId: String,
            bytes: ByteArray,
        ): String? {
            val extension =
                when {
                    bytes.size > MAX_BYTES -> null
                    bytes.startsWith(PNG) -> "png"
                    bytes.startsWith(JPEG) -> "jpg"
                    else -> null
                }
            return extension?.takeIf { ID.matches(businessId) }?.let { "$businessId.$it" }
        }

        private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
