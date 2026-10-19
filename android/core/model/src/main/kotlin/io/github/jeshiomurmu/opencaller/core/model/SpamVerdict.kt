package io.github.jeshiomurmu.opencaller.core.model

/** How risky a caller or sender looks. Shown with an icon and a word, never by color alone (docs/design.md §2). */
enum class RiskLevel {
    /** No spam signal. */
    NONE,

    /** Some reports or weak signals; shown in amber as "Suspicious". */
    SUSPICIOUS,

    /** Strong signals; shown in red as "Likely scam". */
    LIKELY_SPAM,
}

/** Where a spam verdict came from, for the caller card's "Why am I seeing this?". */
enum class VerdictSource {
    /** The spam list downloaded to the phone (docs/architecture.md §4.1 step 8). */
    SPAM_LIST,

    /** Information cached from an earlier live lookup (step 9). */
    REPUTATION_CACHE,

    /** A live k-anonymous lookup during the call (step 10). */
    LIVE_LOOKUP,

    /** A search the user made (the full number was sent, by the user's choice). */
    SEARCH,
}

/**
 * Spam assessment for a number.
 *
 * @property score probability-like score in `0.0..1.0`.
 * @property level user-facing risk level derived from [score] and the user's threshold.
 * @property category community category such as "Loan" or "Telemarketing", if known.
 * @property reportCount number of community reports.
 * @property source where the verdict came from, if known.
 */
data class SpamVerdict(
    val score: Float,
    val level: RiskLevel,
    val category: String?,
    val reportCount: Int,
    val source: VerdictSource? = null,
) {
    init {
        require(score in 0f..1f) { "score must be in 0..1" }
        require(reportCount >= 0) { "reportCount must not be negative" }
    }

    /** How a score maps to a [RiskLevel]. */
    companion object {
        /** Lowest score shown as [RiskLevel.SUSPICIOUS]. */
        const val SUSPICIOUS_MIN_SCORE: Float = 0.4f

        /** [RiskLevel.LIKELY_SPAM] at or above [threshold], [RiskLevel.SUSPICIOUS] from [SUSPICIOUS_MIN_SCORE], else none. */
        fun levelFor(
            score: Float,
            threshold: SpamThreshold,
        ): RiskLevel =
            when {
                score >= threshold.minimumScore -> RiskLevel.LIKELY_SPAM
                score >= SUSPICIOUS_MIN_SCORE -> RiskLevel.SUSPICIOUS
                else -> RiskLevel.NONE
            }
    }
}
