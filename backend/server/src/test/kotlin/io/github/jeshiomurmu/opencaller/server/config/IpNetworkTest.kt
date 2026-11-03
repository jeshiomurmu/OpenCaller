package io.github.jeshiomurmu.opencaller.server.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CIDR blocks of the admin web's allowlist. */
class IpNetworkTest {
    @Test
    fun ipv4BlocksMatchByPrefix() {
        val block = IpNetwork.parse("10.20.0.0/14")!!
        assertTrue(block.contains("10.20.0.1") && block.contains("10.23.255.255"))
        assertFalse(block.contains("10.24.0.0"))
        assertTrue(IpNetwork.parse("203.0.113.7")!!.contains("203.0.113.7"))
        assertFalse(IpNetwork.parse("203.0.113.7")!!.contains("203.0.113.8"))
        assertTrue(IpNetwork.parse("0.0.0.0/0")!!.contains("198.51.100.1"))
    }

    @Test
    fun ipv6BlocksMatchAndFamiliesDontMix() {
        val block = IpNetwork.parse("2001:db8::/32")!!
        assertTrue(block.contains("2001:db8:1::5"))
        assertFalse(block.contains("2001:db9::1"))
        assertFalse(block.contains("10.0.0.1"))
        assertFalse(IpNetwork.parse("10.0.0.0/8")!!.contains("::1"))
        // An IPv4 client on a dual-stack socket is still matched by its IPv4 block.
        assertTrue(IpNetwork.parse("10.0.0.0/8")!!.contains("::ffff:10.1.2.3"))
    }

    @Test
    fun namesAndMalformedBlocksAreRefused() {
        listOf("localhost", "example.com", "10.0.0.0/33", "10.0.0.256", "10.0.0.0/8/1", "10.0.0.0/x", "", "::/129").forEach {
            assertNull(IpNetwork.parse(it), it)
        }
        assertFalse(IpNetwork.parse("10.0.0.0/8")!!.contains("localhost"))
    }
}
