package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.NameSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMappingTest {
    @Test
    fun everyNameSourceHasItsOwnLabelAndIcon() {
        val sources = NameSource.entries
        assertEquals(sources.size, sources.map { it.labelRes() }.toSet().size)
        assertEquals(sources.size, sources.map { it.iconRes() }.toSet().size)
    }
}
