package io.github.jeshiomurmu.opencaller.server.push

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * Which Web Push endpoints the server will POST to. An endpoint comes from the app, so without this check anyone
 * could make the server send requests into its own network (SSRF). In production only `https://` endpoints whose
 * host resolves to public addresses are accepted; [allowPrivate] (development) also allows `http://` and local
 * addresses, for a self-hosted UnifiedPush distributor on the same machine.
 */
class PushEndpointPolicy(
    private val allowPrivate: Boolean,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    /** The endpoint as a URI, or 400 `invalid_push_endpoint`. */
    fun check(endpoint: String): URI {
        val uri =
            try {
                URI(endpoint.trim())
            } catch (e: URISyntaxException) {
                throw invalid("The endpoint is not a URL.", e)
            }
        val schemes = if (allowPrivate) setOf("https", "http") else setOf("https")
        if (endpoint.length > MAX_LENGTH || uri.scheme?.lowercase() !in schemes || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) {
            throw invalid(if (allowPrivate) "Use an http(s) URL." else "Use an https URL.")
        }
        if (!allowPrivate) {
            val addresses =
                try {
                    resolve(uri.host)
                } catch (e: UnknownHostException) {
                    throw invalid("The endpoint's host does not resolve.", e)
                }
            if (addresses.isEmpty() || addresses.any(::isPrivate)) throw invalid("The endpoint must be on the public internet.")
        }
        return uri
    }

    private fun invalid(
        detail: String,
        cause: Throwable? = null,
    ) = ApiProblem(HttpStatusCode.BadRequest, "invalid_push_endpoint", "Invalid push endpoint", detail, cause = cause)

    /** Address checks. */
    companion object {
        private const val MAX_LENGTH = 1024
        private const val CGNAT_FIRST: Int = 100
        private const val CGNAT_SECOND_MASK = 0xC0
        private const val CGNAT_SECOND: Int = 64
        private const val ULA_MASK = 0xFE
        private const val ULA_PREFIX = 0xFC
        private const val BYTE_MASK = 0xFF

        /** Loopback, private, link-local, wildcard, multicast, carrier-grade NAT (100.64/10) or IPv6 unique-local. */
        fun isPrivate(address: InetAddress): Boolean {
            val bytes = address.address
            val special =
                when (address) {
                    is Inet4Address -> {
                        (bytes[0].toInt() and BYTE_MASK) == CGNAT_FIRST &&
                            (bytes[1].toInt() and CGNAT_SECOND_MASK) == CGNAT_SECOND
                    }

                    is Inet6Address -> {
                        (bytes[0].toInt() and ULA_MASK) == ULA_PREFIX
                    }

                    else -> {
                        true
                    }
                }
            return special || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress
        }
    }
}
