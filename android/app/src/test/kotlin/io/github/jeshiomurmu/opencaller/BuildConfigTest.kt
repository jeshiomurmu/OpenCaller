package io.github.jeshiomurmu.opencaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test
    fun debugBuildUsesDebugApplicationIdSuffix() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("io.github.jeshiomurmu.opencaller.debug", BuildConfig.APPLICATION_ID)
    }
}
