package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.ReportCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMappingTest {
    @Test
    fun everyNameSourceHasItsOwnLabelAndIcon() {
        val sources = NameSource.entries
        assertEquals(sources.size, sources.map { it.labelRes() }.toSet().size)
        assertEquals(sources.size, sources.map { it.iconRes() }.toSet().size)
    }

    @Test
    fun everyAccessItemHasItsOwnLabelAndIcon() {
        val items = AccessItem.entries
        assertEquals(items.size, items.map { it.labelRes() }.toSet().size)
        assertEquals(items.size, items.map { it.iconRes() }.toSet().size)
    }

    @Test
    fun everyReportCategoryHasItsOwnLabel() {
        assertEquals(
            ReportCategory.entries.size,
            ReportCategory.entries
                .map { it.labelRes() }
                .toSet()
                .size,
        )
    }
}
