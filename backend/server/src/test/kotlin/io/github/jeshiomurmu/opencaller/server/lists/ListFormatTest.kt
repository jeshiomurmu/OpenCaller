package io.github.jeshiomurmu.opencaller.server.lists

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The binary list format of docs/architecture.md §13. The expected bytes were produced by a separate Python script
 * written from §13 alone (struct.pack, big-endian), so encoder, decoder and documentation agree.
 */
class ListFormatTest {
    private val created = 1_792_152_000L // 2026-10-16T12:00:00Z

    private val full =
        ListFile(
            ListKind.FULL,
            "BD",
            version = 3,
            baseVersion = 0,
            createdAtEpochSeconds = created,
            // Deliberately unsorted: the encoder sorts by number.
            entries = listOf(ListEntry(8_801_712_345_678, 62, null, 7), ListEntry(12_015_550_123, 91, "scam", 214)),
        )

    private val fullHex =
        "4f43534c0101424400000000000000030000000000000000000000006ad211c001047363616d000000020000000000000002cc2ebeab5b00000000d6" +
            "000008014ef0124e3eff000000070447feb7d1a5f7f6e779d9dda92dfea006ae9807cc917f136402a045dba1382d"

    private val delta =
        ListFile(
            ListKind.DELTA,
            "BD",
            version = 4,
            baseVersion = 3,
            createdAtEpochSeconds = created + 86_400,
            entries = listOf(ListEntry(8_801_712_345_678, 70, null, 9)),
            removals = listOf(12_015_550_123),
        )

    private val deltaHex =
        "4f43534c0102424400000000000000040000000000000003000000006ad36340000000000100000001000008014ef0124e46ff0000000900000002cc2e" +
            "beab3f333076f8b32df19c85344d4a2952736aee2d63adf48427e8ce9f7c499c9eb0"

    @Test
    fun encodesExactlyAsDocumented() {
        assertEquals(fullHex, ListFormat.encode(full).toHexString())
        assertEquals(106, ListFormat.encode(full).size)
        assertEquals(deltaHex, ListFormat.encode(delta).toHexString())
    }

    @Test
    fun decodesWhatItEncodes() {
        assertEquals(full.copy(entries = full.entries.sortedBy { it.number }), ListFormat.decode(fullHex.hexToByteArray()))
        assertEquals(delta, ListFormat.decode(deltaHex.hexToByteArray()))
        val big =
            full.copy(
                entries =
                    (1..5_000L).map {
                        ListEntry(
                            8_801_700_000_000 + it * 7,
                            (it % 101).toInt(),
                            listOf("loan", "scam", null)[(it % 3).toInt()],
                            it,
                        )
                    },
            )
        val bytes = ListFormat.encode(big)
        assertEquals(big, ListFormat.decode(bytes))
        assertContentEquals(bytes, ListFormat.encode(ListFormat.decode(bytes)))
    }

    @Test
    fun corruptFilesAreRejected() {
        val bytes = fullHex.hexToByteArray()
        val flipped = bytes.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertFailsWith<ListFormatException> { ListFormat.decode(flipped) }
        assertFailsWith<ListFormatException> { ListFormat.decode(bytes.copyOf(20)) }
        assertFailsWith<ListFormatException> { ListFormat.decode(ByteArray(0)) }
    }

    @Test
    fun invalidListsCannotBeWritten() {
        assertFailsWith<IllegalArgumentException> { ListFormat.encode(full.copy(countryIso = "bd")) }
        assertFailsWith<IllegalArgumentException> { ListFormat.encode(full.copy(entries = full.entries + full.entries.first())) }
        assertFailsWith<IllegalArgumentException> { ListFormat.encode(full.copy(removals = listOf(1))) }
        assertFailsWith<IllegalArgumentException> { ListEntry(1, 101, null, 0) }
    }

    @Test
    fun scoresAreRoundedDownToWholePercent() {
        assertEquals(84, ListEntry.of("+8801712345678", 0.849, null, 1).scorePercent)
        assertEquals(85, ListEntry.of("+8801712345678", 0.85f.toDouble(), null, 1).scorePercent)
        assertEquals(39, ListEntry.of("+8801712345678", 0.39f.toDouble(), null, 1).scorePercent)
        assertEquals(100, ListEntry.of("+8801712345678", 1.0, null, 1).scorePercent)
        assertEquals(8_801_712_345_678, ListEntry.of("+8801712345678", 0.5, null, 1).number)
        assertEquals("+8801712345678", ListEntry.of("+8801712345678", 0.5, null, 1).e164)
    }
}
