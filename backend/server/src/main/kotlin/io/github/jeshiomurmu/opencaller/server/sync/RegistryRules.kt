package io.github.jeshiomurmu.opencaller.server.sync

/**
 * Shapes of registry values, shared by the seed files, the sender-ID importers and the admin web's curation forms
 * (docs/rules.md §7: every input validated).
 */
object RegistryRules {
    /** ISO 3166-1 alpha-2 country, upper case. */
    val ISO = Regex("[A-Z]{2}")

    /** An SMS sender header, e.g. `HDFCBK` (the header part of an Indian `XX-HDFCBK-T`). */
    val HEADER = Regex("[A-Za-z0-9-]{2,20}")

    /** A category slug, e.g. `bank`. */
    val SLUG = Regex("[a-z_]{2,32}")

    /** An SMS Retriever app hash: 11 characters of base64. */
    val APP_HASH = Regex("[A-Za-z0-9+/]{11}")

    /** An Android package name. */
    val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    /** A number prefix in E.164 form, e.g. `+1555010`. */
    val PREFIX = Regex("\\+[1-9][0-9]{0,14}")

    /** A data source name, e.g. `seed-example` or `csv-in-dlt`. */
    val SOURCE = Regex("[a-z0-9-]{2,32}")

    /** Longest brand, app or provider name. */
    const val MAX_NAME = 100

    /** `true` for a name of 1 to [MAX_NAME] characters without control characters or surrounding spaces. */
    fun isName(value: String): Boolean = value.length in 1..MAX_NAME && value.trim() == value && value.none(Char::isISOControl)
}
