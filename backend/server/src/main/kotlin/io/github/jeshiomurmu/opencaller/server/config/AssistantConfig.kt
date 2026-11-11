package io.github.jeshiomurmu.opencaller.server.config

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * The AI call-screening assistant (Phase 13, F-UT-04, ADR 0010). [numbers] is the operator's pool of assistant
 * numbers (E.164 → country, from libphonenumber); each user who turns the assistant on gets one of their country's
 * numbers for themselves, and forwards unanswered calls to it. A user's calls may use [monthlyMinutes] a month
 * (0 = no cap) and each call at most [maxCallMinutes]. The agents share the recording line's token
 * (`RECORDING_AGENT_TOKEN`); the app follows a call through LiveKit, so the assistant also needs `LIVEKIT_*`.
 */
data class AssistantConfig(
    val numbers: Map<String, String> = emptyMap(),
    val monthlyMinutes: Int = DEFAULT_MONTHLY_MINUTES,
    val maxCallMinutes: Int = DEFAULT_MAX_CALL_MINUTES,
) {
    /** Countries that have at least one assistant number. */
    val countries: Set<String> get() = numbers.values.toSet()

    override fun toString(): String =
        "AssistantConfig(numbers=${numbers.size} in ${countries.sorted()}, monthlyMinutes=$monthlyMinutes, maxCallMinutes=$maxCallMinutes)"

    /** Defaults. */
    companion object {
        /** The owner's decision of 2026-11-11: 100 minutes a month per user on the hosted server. */
        const val DEFAULT_MONTHLY_MINUTES = 100

        /** A screening call is a few questions; five minutes ends one that goes nowhere. */
        const val DEFAULT_MAX_CALL_MINUTES = 5
    }
}

private const val MAX_MONTHLY_MINUTES = 100_000
private const val MAX_CALL_MINUTES = 30
private val COUNTRY = Regex("[A-Z]{2}")
private val E164_NUMBER = Regex("\\+[1-9][0-9]{6,14}")

/** The `assistant` section (`ASSISTANT_NUMBERS`, `ASSISTANT_MONTHLY_MINUTES`, `ASSISTANT_MAX_CALL_MINUTES`). */
internal fun ConfigReader.readAssistant(): AssistantConfig =
    AssistantConfig(
        numbers = readAssistantNumbers(),
        monthlyMinutes = int("assistant.monthlyMinutes", "ASSISTANT_MONTHLY_MINUTES", 0..MAX_MONTHLY_MINUTES),
        maxCallMinutes = int("assistant.maxCallMinutes", "ASSISTANT_MAX_CALL_MINUTES", 1..MAX_CALL_MINUTES),
    )

/** The assistant's numbers need the agents' token and LiveKit (the app follows a call in a LiveKit room). */
internal fun ConfigReader.checkAssistant(config: AppConfig) {
    if (config.assistant.numbers.isEmpty()) return
    if (config.recording.agentToken == null) problems += "RECORDING_AGENT_TOKEN is required when ASSISTANT_NUMBERS is set"
    if (config.voip.liveKit == null) {
        problems += "LIVEKIT_URL, LIVEKIT_API_KEY and LIVEKIT_API_SECRET are required when ASSISTANT_NUMBERS is set"
    }
}

private fun ConfigReader.readAssistantNumbers(): Map<String, String> {
    val raw = string("assistant.numbers") ?: return emptyMap()
    val util = PhoneNumberUtil.getInstance()
    val numbers = linkedMapOf<String, String>()
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { number ->
        val region =
            try {
                number.takeIf(E164_NUMBER::matches)?.let { util.getRegionCodeForNumber(util.parse(it, null)) }
            } catch (e: NumberParseException) {
                null.also { problems += "ASSISTANT_NUMBERS has a number libphonenumber can't parse (${e.errorType})" }
            }
        when {
            region == null || !COUNTRY.matches(region) -> {
                problems += "ASSISTANT_NUMBERS must list E.164 numbers of a country, e.g. +8809XXXXXXXXX"
            }

            numbers.put(number, region) != null -> {
                problems += "ASSISTANT_NUMBERS lists a number twice"
            }
        }
    }
    return numbers
}
