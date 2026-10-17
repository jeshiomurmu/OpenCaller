package io.github.jeshiomurmu.opencaller.server.scoring

/** Labels for scores (docs/architecture.md §9): `likely_spam` from 0.85, `suspicious` from 0.6, otherwise none. */
object SpamLabels {
    /** Threshold for "Likely spam". */
    const val LIKELY_SPAM = 0.85

    /** Threshold for "Suspicious". */
    const val SUSPICIOUS = 0.6

    /** API label for [score], or `null` below [SUSPICIOUS]. */
    fun of(score: Double): String? =
        when {
            score >= LIKELY_SPAM -> "likely_spam"
            score >= SUSPICIOUS -> "suspicious"
            else -> null
        }
}

/** Report categories the API accepts (enum whitelist, docs/rules.md §7); apps show their own translated names. */
val REPORT_CATEGORIES: Set<String> =
    setOf(
        "scam",
        "fraud",
        "telemarketing",
        "loan",
        "debt_collector",
        "robocall",
        "survey",
        "political",
        "charity",
        "nuisance",
        "other",
    )
