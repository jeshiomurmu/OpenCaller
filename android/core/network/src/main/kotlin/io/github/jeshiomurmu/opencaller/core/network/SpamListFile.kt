package io.github.jeshiomurmu.opencaller.core.network

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.security.MessageDigest

/** A list file's kind: a full list, or a delta that turns `baseVersion` into `version`. */
enum class SpamListKind(
    internal val code: Int,
) {
    FULL(1),
    DELTA(2),
}

/**
 * One listed number.
 *
 * @property e164 the number in E.164.
 * @property scorePercent spam score in whole percent (0–100, rounded down).
 */
data class SpamListFileEntry(
    val e164: String,
    val scorePercent: Int,
    val category: String?,
    val reportCount: Long,
) {
    /** The score as `0.0..1.0`. */
    val score: Float get() = scorePercent / PERCENT
}

/** A decoded list file (docs/architecture.md §13). [removals] holds E.164 numbers and is empty for a full list. */
data class SpamListFile(
    val kind: SpamListKind,
    val countryIso: String,
    val version: Long,
    val baseVersion: Long,
    val createdAtEpochSeconds: Long,
    val entries: List<SpamListFileEntry>,
    val removals: List<String> = emptyList(),
)

/** The file is not a valid list file (bad checksum, wrong magic, truncated…). */
class SpamListFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads the binary spam-list format of docs/architecture.md §13 (the server writes it in `lists/ListFormat.kt`):
 * big-endian header, sorted 14-byte entries, sorted removals and a SHA-256 trailer over everything before it.
 */
object SpamListFormat {
    private const val MAGIC = "OCSL"
    private const val FORMAT_VERSION = 1
    private const val NO_CATEGORY = 0xFF
    private const val SHA256_BYTES = 32
    private const val U32_MASK = 0xFFFF_FFFFL
    private const val MAX_PERCENT = 100

    /** Hex SHA-256 of a whole file, as the manifest lists it. */
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    /** Decodes [bytes], checking the trailer checksum, magic, format revision, value ranges and sort order. */
    fun decode(bytes: ByteArray): SpamListFile {
        ensure(bytes.size >= SHA256_BYTES) { "file too short" }
        val body = bytes.copyOfRange(0, bytes.size - SHA256_BYTES)
        val trailer = bytes.copyOfRange(body.size, bytes.size)
        ensure(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), trailer)) { "checksum mismatch" }
        return try {
            DataInputStream(ByteArrayInputStream(body)).use(::readBody)
        } catch (e: EOFException) {
            throw SpamListFormatException("truncated file", e)
        }
    }

    private fun readBody(input: DataInputStream): SpamListFile {
        ensure(String(input.readNBytes(MAGIC.length), Charsets.US_ASCII) == MAGIC) { "not a list file" }
        val format = input.readUnsignedByte()
        ensure(format == FORMAT_VERSION) { "unsupported format $format" }
        val kindCode = input.readUnsignedByte()
        val kind = SpamListKind.entries.firstOrNull { it.code == kindCode }
        ensure(kind != null) { "unknown kind $kindCode" }
        val country = String(input.readNBytes(2), Charsets.US_ASCII)
        val version = input.readLong()
        val base = input.readLong()
        val created = input.readLong()
        val categories = List(input.readUnsignedByte()) { String(input.readNBytes(input.readUnsignedByte()), Charsets.UTF_8) }
        val entryCount = (input.readInt().toLong() and U32_MASK).toInt()
        val removalCount = (input.readInt().toLong() and U32_MASK).toInt()
        ensure(entryCount >= 0 && removalCount >= 0) { "too many entries" }
        val numbers = LongArray(entryCount)
        val entries =
            List(entryCount) { i ->
                numbers[i] = input.readLong()
                val score = input.readUnsignedByte()
                val categoryIndex = input.readUnsignedByte()
                val reports = input.readInt().toLong() and U32_MASK
                ensure(score <= MAX_PERCENT) { "score out of range" }
                ensure(categoryIndex == NO_CATEGORY || categoryIndex < categories.size) { "unknown category" }
                SpamListFileEntry("+${numbers[i]}", score, categories.getOrNull(categoryIndex), reports)
            }
        val removals = LongArray(removalCount) { input.readLong() }
        ensure(input.available() == 0) { "trailing bytes" }
        ensure(numbers.isStrictlyAscending() && removals.isStrictlyAscending()) { "entries are not strictly ascending" }
        ensure(kind == SpamListKind.DELTA || removals.isEmpty()) { "a full list has removals" }
        return SpamListFile(checkNotNull(kind), country, version, base, created, entries, removals.map { "+$it" })
    }

    private fun LongArray.isStrictlyAscending(): Boolean = (1 until size).all { this[it - 1] in 1 until this[it] }

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw SpamListFormatException(message())
    }
}

/**
 * Which files bring a phone from [localVersion] of [localCountry] to the manifest's version: the chain of deltas
 * when the manifest covers every step, otherwise the full list. `null` means nothing to do.
 */
fun ListManifestDto.updatePlan(
    localCountry: String?,
    localVersion: Long?,
): List<ListFileRefDto>? {
    val upToDate = localCountry == countryIso && localVersion == version
    val chain = if (localCountry == countryIso && localVersion != null) deltaChain(localVersion) else null
    return when {
        upToDate -> null
        chain != null -> chain
        else -> listOf(full)
    }
}

// The deltas from [from] to the latest version, or null when the manifest doesn't cover every step.
private fun ListManifestDto.deltaChain(from: Long): List<ListFileRefDto>? {
    if (from >= version) return null
    val byBase = deltas.associateBy { it.baseVersion }
    val chain = generateSequence(byBase[from]) { previous -> byBase[previous.version] }.take(deltas.size).toList()
    return chain.takeIf { it.lastOrNull()?.version == version && it.zipWithNext().all { (a, b) -> b.baseVersion == a.version } }
}

private const val PERCENT = 100f
