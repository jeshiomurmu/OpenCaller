package io.github.jeshiomurmu.opencaller

import org.junit.Assert.assertEquals
import org.junit.Test

class DistributionFlavorTest {
    @Test
    fun buildsTheFossFlavor() {
        assertEquals("foss", BuildConfig.FLAVOR)
    }
}
