package io.github.jeshiomurmu.opencaller.server.config

import java.net.InetAddress

/**
 * A CIDR block such as `10.0.0.0/8`, `203.0.113.7/32` or `2001:db8::/32`, for the admin web's IP allowlist. Only
 * numeric addresses are accepted (no host names, so parsing never touches DNS).
 */
class IpNetwork private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
    private val text: String,
) {
    /** `true` if [address] (a numeric IPv4 or IPv6 address) lies in this block; anything else is `false`. */
    fun contains(address: String): Boolean {
        val bytes = numericBytes(address)
        return bytes != null && bytes.size == network.size && prefixMatches(bytes)
    }

    private fun prefixMatches(bytes: ByteArray): Boolean {
        val fullBytes = prefixLength / BITS
        val rest = prefixLength % BITS
        val mask = (FULL_BYTE shl (BITS - rest)) and FULL_BYTE
        return (0 until fullBytes).all { bytes[it] == network[it] } &&
            (rest == 0 || (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask))
    }

    override fun toString() = text

    /** Parsing. */
    companion object {
        private const val BITS = 8
        private const val FULL_BYTE = 0xFF
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""[0-9A-Fa-f:.]{2,45}""")

        /** The block written as `address/prefix` (or a bare address, meaning that one address), or `null` if invalid. */
        fun parse(cidr: String): IpNetwork? {
            val parts = cidr.trim().split('/')
            val bytes = parts.takeIf { it.size <= 2 }?.let { numericBytes(it[0]) }
            val maxPrefix = (bytes?.size ?: 0) * BITS
            val prefix = if (parts.size == 2) parts[1].toIntOrNull() else maxPrefix
            return if (bytes != null && prefix != null && prefix in 0..maxPrefix) IpNetwork(bytes, prefix, "${parts[0]}/$prefix") else null
        }

        // InetAddress.getByName resolves names through DNS, so only strings shaped like numeric addresses reach it;
        // for those it doesn't look anything up and fails only on malformed input, which is `null` here.
        private fun numericBytes(address: String): ByteArray? {
            val numeric =
                (IPV4.matches(address) && address.split('.').all { it.toInt() <= FULL_BYTE }) ||
                    (':' in address && IPV6.matches(address))
            return if (numeric) runCatching { InetAddress.getByName(address).address }.getOrNull() else null
        }
    }
}
