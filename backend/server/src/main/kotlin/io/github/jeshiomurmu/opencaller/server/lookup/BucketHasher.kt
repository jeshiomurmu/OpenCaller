package io.github.jeshiomurmu.opencaller.server.lookup

import java.security.MessageDigest

/**
 * k-anonymous lookup buckets (docs/architecture.md §4.6): the first [bits] bits of SHA-256(UTF-8 bytes of the
 * E.164 string), read big-endian as an unsigned integer. Must match the app's `core:phonenumber` BucketHasher;
 * both are tested against `fixtures/bucket_vectors.csv`.
 */
class BucketHasher(
    private val bits: Int = DEFAULT_BITS,
) {
    init {
        require(bits in 1..MAX_BITS) { "bits must be in 1..$MAX_BITS" }
    }

    /** Bucket id of the E.164 number [e164], in `0 until 2^bits`. */
    fun bucket(e164: String): Int {
        val digest = digest(e164)
        var top = 0L
        for (i in 0 until Int.SIZE_BYTES) {
            top = (top shl Byte.SIZE_BITS) or (digest[i].toLong() and BYTE_MASK)
        }
        return (top ushr (Int.SIZE_BITS - bits)).toInt()
    }

    /** Full SHA-256 digest of [e164], returned to clients so they can match bucket entries locally. */
    fun digest(e164: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(e164.toByteArray(Charsets.UTF_8))

    /** Bucket size limits. */
    companion object {
        /** Default bucket size from docs/architecture.md §4.6. */
        const val DEFAULT_BITS = 16

        /** Largest bucket id width that still fits a non-negative [Int]. */
        const val MAX_BITS = 31

        private const val BYTE_MASK = 0xFFL
    }
}
