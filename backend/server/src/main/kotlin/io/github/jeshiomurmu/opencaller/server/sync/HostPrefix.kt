package io.github.jeshiomurmu.opencaller.server.sync

import java.net.IDN
import java.security.MessageDigest

/**
 * The hash prefix of a link host, as the app computes it (`core:ml` `Hosts.prefix`): the first 4 bytes of SHA-256
 * over the host's lower-case ASCII (IDNA) form in UTF-8, written as 8 hex digits. Both sides are tested against
 * `fixtures/host_prefix_vectors.csv` (CI checks the two copies are identical).
 */
object HostPrefix {
    private const val PREFIX_BYTES = 4

    /** The prefix of [host]. */
    fun of(host: String): String {
        val ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(ascii.toByteArray(Charsets.UTF_8))
        return digest.take(PREFIX_BYTES).joinToString("") { "%02x".format(it) }
    }
}
