package io.github.jeshiomurmu.opencaller.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The app reads the list format exactly as the server writes it: these bytes are the server's own test vectors
 * (backend/server ListFormatTest, the example in docs/architecture.md §13).
 */
class SpamListFormatTest {
    private val fullHex =
        "4f43534c0101424400000000000000030000000000000000000000006ad211c001047363616d000000020000000000000002cc2ebeab5b00000000d6" +
            "000008014ef0124e3eff000000070447feb7d1a5f7f6e779d9dda92dfea006ae9807cc917f136402a045dba1382d"

    private val deltaHex =
        "4f43534c0102424400000000000000040000000000000003000000006ad36340000000000100000001000008014ef0124e46ff0000000900000002cc2e" +
            "beab3f333076f8b32df19c85344d4a2952736aee2d63adf48427e8ce9f7c499c9eb0"

    @Test
    fun decodesTheServersFullList() {
        val file = SpamListFormat.decode(fullHex.hexToByteArray())
        assertEquals(SpamListKind.FULL, file.kind)
        assertEquals("BD", file.countryIso)
        assertEquals(3L, file.version)
        assertEquals(0L, file.baseVersion)
        assertEquals(1_792_152_000L, file.createdAtEpochSeconds)
        assertEquals(
            listOf(SpamListFileEntry("+12015550123", 91, "scam", 214), SpamListFileEntry("+8801712345678", 62, null, 7)),
            file.entries,
        )
        assertEquals(0.91f, file.entries.first().score, 1e-6f)
    }

    @Test
    fun decodesTheServersDelta() {
        val file = SpamListFormat.decode(deltaHex.hexToByteArray())
        assertEquals(SpamListKind.DELTA, file.kind)
        assertEquals(4L, file.version)
        assertEquals(3L, file.baseVersion)
        assertEquals(listOf(SpamListFileEntry("+8801712345678", 70, null, 9)), file.entries)
        assertEquals(listOf("+12015550123"), file.removals)
    }

    @Test
    fun rejectsCorruptFiles() {
        val bytes = fullHex.hexToByteArray()
        val flipped = bytes.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        listOf(flipped, bytes.copyOf(20), ByteArray(0), bytes.copyOf(bytes.size - 1)).forEach { bad ->
            assertThrows(SpamListFormatException::class.java) { SpamListFormat.decode(bad) }
        }
    }

    @Test
    fun checksumIsOverTheWholeFile() {
        assertEquals(64, SpamListFormat.sha256Hex(fullHex.hexToByteArray()).length)
    }

    @Test
    fun plansDeltaChainsOrAFullDownload() {
        fun ref(
            version: Long,
            base: Long? = null,
        ) = ListFileRefDto(version, base, "/v1/lists/BD/files/$version/${if (base == null) "full" else "delta"}", "x", "t")
        val manifest = ListManifestDto("BD", 1, 5, ref(5), listOf(ref(5, 4), ref(4, 3), ref(3, 2)))
        assertNull(manifest.updatePlan("BD", 5))
        assertEquals(listOf(ref(3, 2), ref(4, 3), ref(5, 4)), manifest.updatePlan("BD", 2))
        assertEquals(listOf(ref(5, 4)), manifest.updatePlan("BD", 4))
        // Too far behind, nothing local, another country, or newer than the server: full list.
        assertEquals(listOf(ref(5)), manifest.updatePlan("BD", 1))
        assertEquals(listOf(ref(5)), manifest.updatePlan(null, null))
        assertEquals(listOf(ref(5)), manifest.updatePlan("IN", 5))
        assertEquals(listOf(ref(5)), manifest.updatePlan("BD", 9))
        // A gap in the chain: full list.
        assertEquals(listOf(ref(5)), manifest.copy(deltas = listOf(ref(5, 4), ref(3, 2))).updatePlan("BD", 2))
    }
}
