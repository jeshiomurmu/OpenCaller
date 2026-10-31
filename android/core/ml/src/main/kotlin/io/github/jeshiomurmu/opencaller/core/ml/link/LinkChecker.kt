package io.github.jeshiomurmu.opencaller.core.ml.link

import io.github.jeshiomurmu.opencaller.core.ml.text.SmsText
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import javax.inject.Inject

/**
 * OpenCaller's known-bad host list as hash prefixes ([Hosts.prefix]), downloaded from the server. Only prefixes
 * are kept and checked, so neither the list nor a check reveals the links in the user's messages.
 */
fun interface BadHostPrefixes {
    /** True when [prefix] is on the list. */
    suspend fun contains(prefix: Int): Boolean
}

/** What [LinkChecker] found about one link. [imitates] names the brand a lookalike host copies. */
data class LinkFinding(
    val host: String,
    val risks: Set<MessageRisk>,
    val imitates: String? = null,
)

/** Every link in a message. */
data class LinkReport(
    val links: List<LinkFinding> = emptyList(),
) {
    /** The risks of all links together. */
    val risks: Set<MessageRisk> get() = links.flatMapTo(mutableSetOf()) { it.risks }

    /** The first brand a link imitates. */
    val imitates: String? get() = links.firstNotNullOfOrNull { it.imitates }

    /** A link imitates a brand, mixes scripts or is on the known-bad list. */
    val isDangerous: Boolean get() = risks.any { it in STRONG }

    /** A link is shortened or looks like a phishing host (weaker signals). */
    val isDoubtful: Boolean get() = risks.isNotEmpty()

    private companion object {
        val STRONG = setOf(MessageRisk.LOOKALIKE_LINK, MessageRisk.MIXED_SCRIPT_LINK, MessageRisk.LISTED_LINK)
    }
}

/**
 * Checks the links in an SMS (docs/architecture.md §4.4 step 5): hosts that imitate a known brand (letters swapped
 * for look-alikes, the brand name on someone else's domain), hosts that mix scripts, URL shorteners, phishing-style
 * hosts, and the server's known-bad list by hash prefix. Everything runs on the device; no link leaves it.
 */
class LinkChecker
    @Inject
    constructor(
        private val catalog: BrandCatalog,
        private val badHosts: BadHostPrefixes,
    ) {
        /** The links in [text] and their risks. */
        suspend fun check(text: String): LinkReport =
            LinkReport(
                SmsText
                    .links(text)
                    .mapNotNull(Hosts::of)
                    .distinct()
                    .map { host -> finding(host) },
            )

        private suspend fun finding(host: String): LinkFinding {
            val data = catalog.data
            val imitated = Lookalikes.imitated(host, data.brands)
            val risks =
                buildSet {
                    if (imitated != null) add(MessageRisk.LOOKALIKE_LINK)
                    if (Confusables.mixesScripts(host)) add(MessageRisk.MIXED_SCRIPT_LINK)
                    if (Hosts.registrable(host) in data.shorteners || host in data.shorteners) add(MessageRisk.SHORTENED_LINK)
                    if (looksLikePhishing(host, data)) add(MessageRisk.SUSPICIOUS_LINK)
                    if (Hosts.chain(host).any { badHosts.contains(Hosts.prefix(it)) }) add(MessageRisk.LISTED_LINK)
                }
            return LinkFinding(host = host, risks = risks, imitates = imitated?.name)
        }

        private fun looksLikePhishing(
            host: String,
            data: LinkData,
        ): Boolean {
            val words = host.substringBeforeLast('.').split('.', '-')
            return host.substringAfterLast('.') in data.suspiciousTlds || words.any { it in data.hostWords }
        }
    }

/** Finds the brand a host imitates. */
internal object Lookalikes {
    private const val FUZZY_LENGTH = 6

    /** The brand [host] imitates, or `null` when it names none or is one of the brand's own domains. */
    fun imitated(
        host: String,
        brands: List<Brand>,
    ): Brand? {
        val skeleton = Confusables.skeleton(host)
        val labels = skeleton.split('.').dropLast(1)
        val tokens = labels.flatMap { it.split('-') }
        val joined = labels.map { it.replace("-", "") }
        return brands.firstOrNull { brand ->
            !ownsHost(brand, host) && brand.labels.any { label -> names(label, tokens, joined) }
        }
    }

    private fun ownsHost(
        brand: Brand,
        host: String,
    ): Boolean = brand.domains.any { host == it || host.endsWith(".$it") }

    // Short labels ("sbi") must stand alone; long ones ("paypal") also match one letter off or inside a longer label.
    private fun names(
        label: String,
        tokens: List<String>,
        joined: List<String>,
    ): Boolean =
        label in tokens ||
            (
                label.length >= FUZZY_LENGTH &&
                    (joined.any { label in it } || tokens.any { it.length >= FUZZY_LENGTH && oneEditApart(it, label) })
            )

    // Levenshtein distance exactly 1 (one letter added, removed or changed).
    internal fun oneEditApart(
        a: String,
        b: String,
    ): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        var i = 0
        while (i < short.length && short[i] == long[i]) i++
        val rest = if (short.length == long.length) i + 1 else i
        return short.substring(rest) == long.substring(i + 1)
    }
}
