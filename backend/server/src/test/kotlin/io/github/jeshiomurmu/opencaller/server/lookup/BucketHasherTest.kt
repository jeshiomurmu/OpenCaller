package io.github.jeshiomurmu.opencaller.server.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BucketHasherTest {
    // TC-PN-005 (server side): same vectors as android/core/testing/.../fixtures/bucket_vectors.csv.
    @Test
    fun `matches the vectors shared with the app`() {
        val vectors =
            checkNotNull(javaClass.getResourceAsStream("/fixtures/bucket_vectors.csv"))
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(',') }
        assertTrue(vectors.size >= 30)
        vectors.forEach { row ->
            val e164 = row[0]
            val hasher = BucketHasher(row[1].toInt())
            assertEquals(row[2], hasher.digest(e164).joinToString("") { "%02x".format(it) }, e164)
            assertEquals(row[3].toInt(), hasher.bucket(e164), "$e164/${row[1]}")
        }
    }

    @Test
    fun `rejects out of range widths`() {
        assertFailsWith<IllegalArgumentException> { BucketHasher(0) }
        assertFailsWith<IllegalArgumentException> { BucketHasher(BucketHasher.MAX_BITS + 1) }
    }
}
