package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app's version goes to the server on every request; a 426 raises the app-wide update signal (Phase 15). */
class AppVersionTest {
    private val server = MockWebServer()
    private val tokens = MemoryTokenStore()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun network(versionCode: Int) =
        OpenCallerNetwork(ServerConfig(server.url("/").toString(), versionCode = versionCode), tokens, { 0L })

    private fun response(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", if (code < 400) "application/json" else "application/problem+json")
        .body(body)
        .build()

    private val bucket = """{"countryCode":1,"bucket":7,"bits":16,"entries":[]}"""
    private val upgradeRequired =
        """{"type":"urn:opencaller:problem:upgrade_required","title":"Update OpenCaller","status":426,""" +
            """"code":"upgrade_required"}"""

    @Test
    fun everyRequestCarriesTheVersionCode() =
        runBlocking {
            server.enqueue(response(200, bucket))
            val network = network(versionCode = 1010099)
            assertTrue(apiCall { network.lookupApi.bucket(1, 7, 16) } is AppResult.Success)
            assertEquals("1010099", server.takeRequest().headers[OpenCallerNetwork.APP_VERSION_HEADER])
            assertFalse(network.updateRequired.required.value)
        }

    @Test
    fun aRefusedVersionRaisesTheUpdateSignal() =
        runBlocking {
            server.enqueue(response(426, upgradeRequired))
            val network = network(versionCode = 1000099)
            assertTrue(apiCall { network.lookupApi.bucket(1, 7, 16) } is AppResult.Failure)
            assertTrue(network.updateRequired.required.value)
        }

    @Test
    fun withoutAVersionCodeNoHeaderIsSent() =
        runBlocking {
            server.enqueue(response(200, bucket))
            val network = network(versionCode = 0)
            apiCall { network.lookupApi.bucket(1, 7, 16) }
            assertNull(server.takeRequest().headers[OpenCallerNetwork.APP_VERSION_HEADER])
        }
}
