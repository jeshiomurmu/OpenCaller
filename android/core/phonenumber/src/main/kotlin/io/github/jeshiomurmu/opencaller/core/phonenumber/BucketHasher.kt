package io.github.jeshiomurmu.opencaller.core.phonenumber

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import java.security.MessageDigest

/**
 * k-anonymous lookup buckets (docs/architecture.md §4.6): the bucket id is the first [bits] bits of
 * SHA-256(UTF-8 bytes of the E.164 string), read big-endian as an unsigned integer. The server implements
 * the same function; both are tested against `fixtures/bucket_vectors.csv`.
 */
class BucketHasher(
    private val bits: Int = DEFAULT_BITS,
) {
    init {
        require(bits in 1..MAX_BITS) { "bits must be in 1..$MAX_BITS" }
    }

    /** Bucket id of [number], in `0 until 2^bits`. */
    fun bucket(number: PhoneNumber): Int {
        val digest = digest(number)
        var top = 0L
        for (i in 0 until Int.SIZE_BYTES) {
            top = (top shl Byte.SIZE_BITS) or (digest[i].toLong() and BYTE_MASK)
        }
        return (top ushr (Int.SIZE_BITS - bits)).toInt()
    }

    /** Full SHA-256 digest of [number]; matched locally against the entries of a fetched bucket. */
    fun digest(number: PhoneNumber): ByteArray = MessageDigest.getInstance("SHA-256").digest(number.e164.toByteArray(Charsets.UTF_8))

    /** Bucket size limits. */
    companion object {
        /** Default bucket size from docs/architecture.md §4.6. */
        const val DEFAULT_BITS = 16

        /** Largest bucket id width that still fits a non-negative [Int]. */
        const val MAX_BITS = 31

        private const val BYTE_MASK = 0xFFL
    }
}
