package io.github.jeshiomurmu.opencaller.core.phonenumber

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketHasherTest {
    // TC-PN-005: matches the vectors shared with the backend (generated independently with Python hashlib).
    @Test
    fun tcPn005MatchesSharedTestVectors() {
        val vectors = Fixtures.csv("bucket_vectors.csv")
        assertTrue(vectors.size >= 30)
        vectors.forEach { row ->
            val number = PhoneNumber(row[0])
            val hasher = BucketHasher(row[1].toInt())
            assertEquals(row[0], row[2], hasher.digest(number).toHex())
            assertEquals("${row[0]}/${row[1]}", row[3].toInt(), hasher.bucket(number))
        }
    }

    @Test
    fun tcPn005StableAndInRange() {
        val hasher = BucketHasher()
        val number = PhoneNumber("+8801712345678")
        val first = hasher.bucket(number)
        repeat(10) { assertEquals(first, hasher.bucket(number)) }
        assertTrue(first in 0 until (1 shl BucketHasher.DEFAULT_BITS))
    }

    @Test
    fun fullWidthBucketIsNonNegative() {
        val bucket = BucketHasher(BucketHasher.MAX_BITS).bucket(PhoneNumber("+12015550123"))
        assertTrue(bucket >= 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroBits() {
        BucketHasher(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManyBits() {
        BucketHasher(BucketHasher.MAX_BITS + 1)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
