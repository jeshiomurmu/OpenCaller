package io.github.jeshiomurmu.opencaller.server.config

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * The recording line (Phase 13, ADR 0009; infra/deploy/telephony.md). [lines] maps each line's country (ISO 3166,
 * from libphonenumber) to its number in E.164; [countries] is where recording is approved (`RECORDING_COUNTRIES`,
 * default `BD,IN`; the admin web can override single countries). The agents call `/agent/…` with [agentToken].
 * A confirmed download is deleted after [downloadGraceMinutes]. Off unless lines and a token are set.
 */
data class RecordingConfig(
    val lines: Map<String, String> = emptyMap(),
    val countries: Set<String> = DEFAULT_COUNTRIES,
    val agentToken: String? = null,
    val downloadGraceMinutes: Int = DEFAULT_GRACE_MINUTES,
) {
    /** `true` when the line is configured. */
    val enabled: Boolean get() = lines.isNotEmpty() && agentToken != null

    override fun toString(): String {
        val token = if (agentToken == null) "none" else "***"
        return "RecordingConfig(lines=${lines.keys.sorted()}, countries=${countries.sorted()}, agentToken=$token, " +
            "downloadGraceMinutes=$downloadGraceMinutes)"
    }

    /** Defaults. */
    companion object {
        /** Bangladesh and India: the project owner's decision of 2026-11-10. Every other country needs its own legal review. */
        val DEFAULT_COUNTRIES = setOf("BD", "IN")

        /** Minutes a confirmed download stays on the server (the job runs every 10 minutes, so at most 40). */
        const val DEFAULT_GRACE_MINUTES = 30
    }
}

private const val MAX_GRACE_MINUTES = 50
private val COUNTRY = Regex("[A-Z]{2}")
private val E164_LINE = Regex("\\+[1-9][0-9]{6,14}")

/** The `recording` section. A grace above 50 minutes would break the "deleted ≤ 1 h after download" promise. */
internal fun ConfigReader.readRecording(): RecordingConfig {
    val lines = readLines()
    val token = string("recording.agentToken")
    if (token != null && token.length < MIN_TOKEN_LENGTH) problems += "RECORDING_AGENT_TOKEN must be at least $MIN_TOKEN_LENGTH characters"
    if (lines.isNotEmpty() && token == null) problems += "RECORDING_AGENT_TOKEN is required when RECORDING_LINES is set"
    return RecordingConfig(
        lines = lines,
        countries = readCountries(),
        agentToken = token,
        downloadGraceMinutes = int("recording.downloadGraceMinutes", "RECORDING_DOWNLOAD_GRACE_MINUTES", 0..MAX_GRACE_MINUTES),
    )
}

private const val MIN_TOKEN_LENGTH = 32

private fun ConfigReader.readLines(): Map<String, String> {
    val raw = string("recording.lines") ?: return emptyMap()
    val util = PhoneNumberUtil.getInstance()
    val lines = mutableMapOf<String, String>()
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { number ->
        val region =
            try {
                number.takeIf(E164_LINE::matches)?.let { util.getRegionCodeForNumber(util.parse(it, null)) }
            } catch (e: NumberParseException) {
                null.also { problems += "RECORDING_LINES has a number libphonenumber can't parse (${e.errorType})" }
            }
        when {
            region == null || !COUNTRY.matches(region) -> {
                problems += "RECORDING_LINES must list E.164 numbers of a country, e.g. +8801XXXXXXXXX"
            }

            lines.put(region, number) != null -> {
                problems += "RECORDING_LINES has two numbers in $region; give one line per country"
            }
        }
    }
    return lines
}

// Unset = the default list; set but empty = no country (recording off everywhere until the admin web turns one on).
private fun ConfigReader.readCountries(): Set<String> {
    val raw = rawString("recording.countries") ?: return RecordingConfig.DEFAULT_COUNTRIES
    val countries = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    countries.filterNot(COUNTRY::matches).forEach { problems += "RECORDING_COUNTRIES has an invalid country code: $it" }
    return countries.filter(COUNTRY::matches).toSet()
}
