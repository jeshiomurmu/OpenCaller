package io.github.jeshiomurmu.opencaller.core.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What a backup holds (docs/prd.md F-UT-05): the settings (without the onboarding step), the block rules, the
 * screening log and the message categories. One-time codes are left out; SMS bodies, contacts and the call log are
 * never in it (they stay in Android's own providers). Recordings join when the recording line exists (Phase 13).
 */
@Serializable
data class BackupContents(
    val format: Int = FORMAT,
    val createdAtMillis: Long,
    val settings: String,
    val rules: List<BackupRule>,
    val events: List<BackupEvent>,
    val messages: List<BackupMessage>,
) {
    /** Format revision. */
    companion object {
        /** The current format. */
        const val FORMAT = 1
    }
}

/** A block rule (types and actions by name). */
@Serializable
data class BackupRule(
    val type: String,
    val pattern: String,
    val action: String,
    val appliesTo: String,
    val createdAt: Long,
    val enabled: Boolean,
)

/** One screening decision. */
@Serializable
data class BackupEvent(
    val e164: String? = null,
    val direction: String,
    val decision: String,
    val reason: String,
    val spamScore: Float? = null,
    val stirStatus: Int? = null,
    val latencyMs: Int,
    val at: Long,
)

/**
 * A message's category, with what finds the message again on another phone: its date and address (the Telephony
 * provider's ids differ after a restore).
 */
@Serializable
data class BackupMessage(
    val providerId: Long,
    val date: Long? = null,
    val address: String? = null,
    val category: String,
    val senderName: String? = null,
    val senderSource: String? = null,
    val riskFlags: Int,
    val classifierScore: Float,
    val at: Long,
)

/** The archive couldn't be read (wrong format, corrupt, or from a newer app). */
class BackupFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Gzipped JSON of [BackupContents]; encryption is [BackupCipher]'s. */
object BackupArchive {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** The bytes of [contents]. */
    fun encode(contents: BackupContents): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(BackupContents.serializer(), contents).toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** The contents of [bytes]; throws [BackupFormatException]. */
    fun decode(bytes: ByteArray): BackupContents {
        val contents =
            try {
                val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
                json.decodeFromString(BackupContents.serializer(), text)
            } catch (e: IOException) {
                unreadable("not a backup archive", e)
            } catch (e: SerializationException) {
                unreadable("unreadable backup contents", e)
            } catch (e: IllegalArgumentException) {
                unreadable("unreadable backup contents", e)
            }
        if (contents.format > BackupContents.FORMAT) unreadable("made by a newer OpenCaller (format ${contents.format})")
        return contents
    }
}

/** Throws [BackupFormatException]. */
internal fun unreadable(
    message: String,
    cause: Throwable? = null,
): Nothing = throw BackupFormatException(message, cause)
