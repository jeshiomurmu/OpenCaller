package io.github.jeshiomurmu.opencaller.server.lists

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest

/** File kinds: a full list, or a delta that turns version `baseVersion` into `version`. */
enum class ListKind(
    val code: Int,
    val sqlValue: String,
) {
    FULL(1, "full"),
    DELTA(2, "delta"),
}

/**
 * One listed number. [number] is the E.164 number without `+` as an integer (`+8801712345678` → 8801712345678),
 * [scorePercent] the spam score in whole percent (rounded down), [category] a report category or `null`.
 */
data class ListEntry(
    val number: Long,
    val scorePercent: Int,
    val category: String?,
    val reportCount: Long,
) {
    init {
        require(number > 0) { "number must be positive" }
        require(scorePercent in 0..MAX_PERCENT) { "scorePercent must be in 0..100" }
        require(reportCount in 0..MAX_U32) { "reportCount must fit in 32 bits" }
    }

    /** The number in E.164. */
    val e164: String get() = "+$number"

    /** Conversions. */
    companion object {
        private const val MAX_PERCENT = 100
        private const val MAX_U32 = 0xFFFF_FFFFL

        // Scores come from REAL (float) columns: 0.39f is 0.38999998…, which must still be 39 %.
        private const val ROUNDING_SLACK = 1e-4

        /** Entry for E.164 [e164] with spam [score] (0..1); scores are rounded down so 0.849 never shows as 85 %. */
        fun of(
            e164: String,
            score: Double,
            category: String?,
            reportCount: Long,
        ): ListEntry =
            ListEntry(
                number = e164.removePrefix("+").toLong(),
                scorePercent = (score * MAX_PERCENT + ROUNDING_SLACK).toInt().coerceIn(0, MAX_PERCENT),
                category = category,
                reportCount = reportCount.coerceAtMost(MAX_U32),
            )
    }
}

/** A decoded list file. [removals] is empty for a full list. */
data class ListFile(
    val kind: ListKind,
    val countryIso: String,
    val version: Long,
    val baseVersion: Long,
    val createdAtEpochSeconds: Long,
    val entries: List<ListEntry>,
    val removals: List<Long> = emptyList(),
)

/** The list file was malformed or its checksum didn't match. */
class ListFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The binary spam-list format, docs/architecture.md §13. Big-endian throughout:
 *
 *     magic "OCSL" · format u8 (1) · kind u8 · country 2×ASCII · version u64 · baseVersion u64 · createdAt u64
 *     · categoryCount u8 · categories (u8 length + UTF-8) · entryCount u32 · removalCount u32
 *     · entries (number u64, score u8, category index u8 or 0xFF, reportCount u32), ascending by number
 *     · removals (number u64), ascending · SHA-256 of all preceding bytes (32 bytes)
 */
object ListFormat {
    /** File signature. */
    const val MAGIC = "OCSL"

    /** Format revision written by this server. */
    const val FORMAT_VERSION = 1

    /** Bytes per entry. */
    const val ENTRY_BYTES = 14

    private const val NO_CATEGORY = 0xFF
    private const val MAX_CATEGORIES = 255
    private const val MAX_CATEGORY_BYTES = 255
    private const val SHA256_BYTES = 32
    private const val U32_MASK = 0xFFFF_FFFFL
    private const val U8_MASK = 0xFF
    private const val HEADER_LONGS = 3

    /** Encodes [file]; entries and removals are sorted here, duplicates are rejected. */
    fun encode(file: ListFile): ByteArray {
        require(file.countryIso.length == 2 && file.countryIso.all { it in 'A'..'Z' }) { "countryIso must be two letters A-Z" }
        val entries = file.entries.sortedBy { it.number }
        val removals = file.removals.sorted()
        require(entries.zipWithNext().none { (a, b) -> a.number == b.number }) { "duplicate entry" }
        require(removals.zipWithNext().none { (a, b) -> a == b }) { "duplicate removal" }
        require(file.kind == ListKind.DELTA || removals.isEmpty()) { "a full list has no removals" }
        val categories = entries.mapNotNull { it.category }.distinct().sorted()
        require(categories.size <= MAX_CATEGORIES) { "too many categories" }
        val index = categories.withIndex().associate { (i, c) -> c to i }
        val bytes = ByteArrayOutputStream(headerSize(categories) + entries.size * ENTRY_BYTES + removals.size * Long.SIZE_BYTES)
        DataOutputStream(bytes).run {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeByte(FORMAT_VERSION)
            writeByte(file.kind.code)
            write(file.countryIso.toByteArray(Charsets.US_ASCII))
            writeLong(file.version)
            writeLong(file.baseVersion)
            writeLong(file.createdAtEpochSeconds)
            writeByte(categories.size)
            categories.forEach { category ->
                val utf8 = category.toByteArray(Charsets.UTF_8)
                require(utf8.size in 1..MAX_CATEGORY_BYTES) { "category too long" }
                writeByte(utf8.size)
                write(utf8)
            }
            writeInt(entries.size)
            writeInt(removals.size)
            entries.forEach { entry ->
                writeLong(entry.number)
                writeByte(entry.scorePercent)
                writeByte(entry.category?.let(index::getValue) ?: NO_CATEGORY)
                writeInt(entry.reportCount.toInt())
            }
            removals.forEach(::writeLong)
            flush()
        }
        val body = bytes.toByteArray()
        return body + sha256(body)
    }

    /** Decodes and verifies [bytes] (checksum, magic, format, sort order). */
    fun decode(bytes: ByteArray): ListFile {
        ensure(bytes.size >= SHA256_BYTES) { "file too short" }
        val body = bytes.copyOfRange(0, bytes.size - SHA256_BYTES)
        ensure(MessageDigest.isEqual(sha256(body), bytes.copyOfRange(body.size, bytes.size))) { "checksum mismatch" }
        return try {
            DataInputStream(body.inputStream()).use { input -> readBody(input) }
        } catch (e: EOFException) {
            throw ListFormatException("truncated file", e)
        }
    }

    private fun readBody(input: DataInputStream): ListFile {
        ensure(String(input.readNBytes(MAGIC.length), Charsets.US_ASCII) == MAGIC) { "not a list file" }
        val format = input.readUnsignedByte()
        ensure(format == FORMAT_VERSION) { "unsupported format $format" }
        val kindCode = input.readUnsignedByte()
        val kind = ListKind.entries.firstOrNull { it.code == kindCode }
        ensure(kind != null) { "unknown kind $kindCode" }
        val country = String(input.readNBytes(2), Charsets.US_ASCII)
        val version = input.readLong()
        val base = input.readLong()
        val created = input.readLong()
        val categories = List(input.readUnsignedByte()) { String(input.readNBytes(input.readUnsignedByte()), Charsets.UTF_8) }
        val entryCount = input.readInt().toLong() and U32_MASK
        val removalCount = input.readInt().toLong() and U32_MASK
        val entries =
            List(entryCount.toInt()) {
                val number = input.readLong()
                val score = input.readUnsignedByte()
                val category = input.readUnsignedByte().let { if (it == NO_CATEGORY) null else categories.getOrNull(it and U8_MASK) }
                ListEntry(number, score, category, input.readInt().toLong() and U32_MASK)
            }
        val removals = List(removalCount.toInt()) { input.readLong() }
        ensure(input.available() == 0) { "trailing bytes" }
        ensure(entries.zipWithNext().all { (a, b) -> a.number < b.number } && removals.zipWithNext().all { (a, b) -> a < b }) {
            "entries are not strictly ascending"
        }
        return ListFile(checkNotNull(kind), country, version, base, created, entries, removals)
    }

    /** Hex SHA-256 of a whole file, as stored in `list_versions.sha256` and the manifest. */
    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHexString()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw ListFormatException(message())
    }

    // magic, format + kind, country, version + baseVersion + createdAt, categories, entry + removal counts.
    private fun headerSize(categories: List<String>) =
        MAGIC.length + 2 + 2 + HEADER_LONGS * Long.SIZE_BYTES + 1 + categories.sumOf { it.length + 1 } + 2 * Int.SIZE_BYTES
}
