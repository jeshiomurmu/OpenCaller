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
}
