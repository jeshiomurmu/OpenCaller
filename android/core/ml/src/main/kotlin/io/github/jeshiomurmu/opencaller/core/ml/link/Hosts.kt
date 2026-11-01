package io.github.jeshiomurmu.opencaller.core.ml.link

import java.net.IDN
import java.security.MessageDigest

/** Host names: taking one from a link, its registrable domain and its hash prefix. */
object Hosts {
    private const val PREFIX_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val COUNTRY_TLD_LENGTH = 2
    private const val UNDER_SECOND_LEVEL = 3
    private const val UNDER_TOP_LEVEL = 2
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu", "gen", "firm", "ind", "bank", "mil")

    /** The host of [link] (`https://user@a.example:8080/x` → `a.example`), lower case, without `www.`; `null` if none. */
    fun of(link: String): String? {
        val withoutScheme = link.substringAfter("://", link)
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host =
            authority
                .substringAfterLast('@')
                .substringBefore(':')
                .trimEnd('.')
                .lowercase()
                .removePrefix("www.")
        return host.takeIf { it.contains('.') && !it.startsWith('.') }
    }

    /**
     * The part of [host] a person registers (`login.bank.example` → `bank.example`, `a.nagad.com.bd` → `nagad.com.bd`).
     * Approximate: common second-level labels under a two-letter country domain, not the full Public Suffix List.
     */
    fun registrable(host: String): String {
        val labels = host.split('.')
        val underSecondLevel =
            labels.size >= UNDER_SECOND_LEVEL && labels.last().length == COUNTRY_TLD_LENGTH && labels[labels.size - 2] in SECOND_LEVEL
        val keep = if (underSecondLevel) UNDER_SECOND_LEVEL else UNDER_TOP_LEVEL
        return labels.takeLast(keep).joinToString(".")
    }

    /**
     * The host and its parent domains down to the registrable one (`a.b.example` → `a.b.example`, `b.example`), in the
     * ASCII form of internationalised names, as checked against the known-bad list.
     */
    fun chain(host: String): List<String> {
        val ascii = ascii(host)
        val stop = registrable(ascii)
        return generateSequence(ascii) {
            it.substringAfter('.', "").takeIf { parent ->
                parent.length >= stop.length && parent.isNotEmpty()
            }
        }.toList()
    }

    /**
     * The first 4 bytes, big-endian, of SHA-256 of [host] in lower-case ASCII form (UTF-8). The server's known-bad
     * list holds these prefixes; both sides are tested against `fixtures/host_prefix_vectors.csv`.
     */
    fun prefix(host: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(ascii(host).toByteArray(Charsets.UTF_8))
        return (0 until PREFIX_BYTES).fold(0) { value, i -> (value shl BITS_PER_BYTE) or (digest[i].toInt() and BYTE_MASK) }
    }

    // Punycode for internationalised names; a name IDN refuses (too long, bad characters) is hashed as it is.
    private fun ascii(host: String): String = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()
}
