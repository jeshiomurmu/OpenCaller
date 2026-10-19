package io.github.jeshiomurmu.opencaller.di

import io.github.jeshiomurmu.opencaller.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun debugBuildsTalkToTheHostMachine() {
        val config = ServerModule.serverConfig()
        assertEquals("http://10.0.2.2:8080/", config.baseUrl)
        assertTrue(config.isConfigured)
        assertTrue(config.pins.isEmpty())
        assertEquals("OpenCaller/${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})", config.userAgent)
        assertEquals(BuildConfig.FLAVOR, config.flavor)
    }

    @Test
    fun noUrlMeansNoServer() {
        val config = serverConfigOf(baseUrl = " ", pins = "", flavor = "foss", versionName = "0.1.0-alpha")
        assertNull(config.baseUrl)
        assertFalse(config.isConfigured)
        assertNull(config.unlistPageUrl)
    }

    @Test
    fun onlyThePlayFlavorPins() {
        val pins = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=, sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        val play = serverConfigOf("https://api.example.org/", pins, "play", "0.1.0-alpha")
        val foss = serverConfigOf("https://api.example.org/", pins, "foss", "0.1.0-alpha")
        assertEquals(2, play.pins.size)
        assertEquals("sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", play.pins[1])
        assertTrue(foss.pins.isEmpty())
        assertEquals("https://api.example.org/unlist", play.unlistPageUrl)
    }
}
