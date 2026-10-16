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

/**
 * Spam assessment for a number.
 *
 * @property score probability-like score in `0.0..1.0`.
 * @property level user-facing risk level derived from [score] and the user's threshold.
 * @property category community category such as "Loan" or "Telemarketing", if known.
 * @property reportCount number of community reports.
 */
data class SpamVerdict(
    val score: Float,
    val level: RiskLevel,
    val category: String?,
    val reportCount: Int,
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
