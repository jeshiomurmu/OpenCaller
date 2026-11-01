package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupporterBadgeTest {
    // The server sends `supporter` / `gold`; the reputation cache stores the enum names.
    @Test
    fun badgesParseFromTheServerAndTheCache() {
        assertEquals(SupporterBadge.SUPPORTER, SupporterBadge.parse("supporter"))
        assertEquals(SupporterBadge.GOLD, SupporterBadge.parse("gold"))
        assertEquals(SupporterBadge.GOLD, SupporterBadge.parse("GOLD"))
        assertNull(SupporterBadge.parse(null))
        assertNull(SupporterBadge.parse("none"))
        assertNull(SupporterBadge.parse("platinum"))
    }
}
