package io.github.jeshiomurmu.opencaller.core.model

/**
 * Category of a spam report: the server's whitelist (backend `REPORT_CATEGORIES`), which is also the local default
 * list the report sheet shows. [code] is what the API sends and the spam list stores.
 */
enum class ReportCategory(
    val code: String,
) {
    /** Scam or phishing. */
    SCAM("scam"),

    /** Fraud (fake bank, fake government). */
    FRAUD("fraud"),

    /** Sales calls. */
    TELEMARKETING("telemarketing"),

    /** Loan offers. */
    LOAN("loan"),

    /** Debt collection. */
    DEBT_COLLECTOR("debt_collector"),

    /** Recorded messages. */
    ROBOCALL("robocall"),

    /** Surveys. */
    SURVEY("survey"),

    /** Political calls. */
    POLITICAL("political"),

    /** Charity requests. */
    CHARITY("charity"),

    /** Nuisance or silent calls. */
    NUISANCE("nuisance"),

    /** Anything else. */
    OTHER("other"),
    ;

    /** Lookup by [code]. */
    companion object {
        /** The category for [code] (case-insensitive), or `null` for an unknown one. */
        fun fromCode(code: String?): ReportCategory? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}
