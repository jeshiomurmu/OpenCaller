package io.github.jeshiomurmu.opencaller.server.push

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The server never posts Web Push messages into its own network (SSRF). */
class PushEndpointPolicyTest {
    private fun production(vararg addresses: String) =
        PushEndpointPolicy(allowPrivate = false) { _ -> addresses.map { InetAddress.getByName(it) } }

    @Test
    fun publicHttpsEndpointsAreAccepted() {
        assertEquals("push.example.org", production("93.184.216.34").check("https://push.example.org/up/abc").host)
        assertEquals("push.example.org", production("2606:2800:220:1::1").check("https://push.example.org/up").host)
    }

    @Test
    fun privateLocalAndPlainHttpEndpointsAreRefused() {
        val refused =
            listOf(
                "127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1",
                "0.0.0.0", "::1", "fd00::1", "fe80::1", "224.0.0.1",
            )
        refused.forEach { address ->
            val problem = assertFailsWith<ApiProblem>(address) { production(address).check("https://push.example.org/up") }
            assertEquals("invalid_push_endpoint", problem.code)
        }
        // One private address among public ones is enough to refuse.
        assertFailsWith<ApiProblem> { production("93.184.216.34", "10.0.0.1").check("https://push.example.org/up") }
        assertFailsWith<ApiProblem> { production("93.184.216.34").check("http://push.example.org/up") }
        assertFailsWith<ApiProblem> { production("93.184.216.34").check("https://user:pw@push.example.org/up") }
        assertFailsWith<ApiProblem> { production("93.184.216.34").check("ftp://push.example.org/up") }
    }

    @Test
    fun developmentAllowsALocalDistributor() {
        assertEquals("localhost", PushEndpointPolicy(allowPrivate = true).check("http://localhost:8090/up/abc").host)
    }
}
