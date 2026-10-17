package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportCategoryTest {
    @Test
    fun codesAreTheServersWhitelist() {
        // backend: scoring/SpamLabels.kt and openapi.yaml; a change there needs one here.
        assertEquals(
            listOf(
                "scam",
                "fraud",
                "telemarketing",
                "loan",
                "debt_collector",
                "robocall",
                "survey",
                "political",
                "charity",
                "nuisance",
                "other",
            ),
            ReportCategory.entries.map { it.code },
        )
    }

    @Test
    fun lookupIgnoresCase() {
        assertEquals(ReportCategory.LOAN, ReportCategory.fromCode("Loan"))
        assertEquals(ReportCategory.DEBT_COLLECTOR, ReportCategory.fromCode(" debt_collector "))
        assertNull(ReportCategory.fromCode("lottery"))
        assertNull(ReportCategory.fromCode(null))
    }
}
