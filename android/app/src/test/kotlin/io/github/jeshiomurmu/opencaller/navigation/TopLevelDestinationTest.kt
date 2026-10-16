package io.github.jeshiomurmu.opencaller.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun fiveTabsInDesignOrder() {
        assertEquals(listOf("CALLS", "MESSAGES", "CONTACTS", "PROTECTION", "ME"), TopLevelDestination.entries.map { it.name })
    }

    @Test
    fun eachTabHasItsOwnRouteLabelIconAndPlaceholder() {
        val tabs = TopLevelDestination.entries
        assertEquals(tabs.size, tabs.map { it.route }.toSet().size)
        assertEquals(tabs.size, tabs.map { it.label }.toSet().size)
        assertEquals(tabs.size, tabs.map { it.icon }.toSet().size)
        assertEquals(tabs.size, tabs.map { it.placeholder }.toSet().size)
    }
}
