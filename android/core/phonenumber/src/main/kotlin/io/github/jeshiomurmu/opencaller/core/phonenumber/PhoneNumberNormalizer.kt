package io.github.jeshiomurmu.opencaller.core.phonenumber

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber as LibPhoneNumber

/**
 * Turns user input and telephony handles into [PhoneNumber]s (E.164) with libphonenumber's offline metadata.
 *
 * Region arguments are ISO 3166-1 alpha-2 codes (`BD`, `IN`, …), case-insensitive. Never throws: invalid
 * input becomes [AppError.Validation].
 */
@Suppress("TooManyFunctions") // The app's one façade over libphonenumber: every number question goes through it.
class PhoneNumberNormalizer(
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
) {
    /** Parses [raw] (national or international format) using [defaultRegion] for national numbers. */
    fun normalize(
        raw: String,
        defaultRegion: String,
    ): AppResult<PhoneNumber> {
        val input = raw.trim()
        if (input.isEmpty() || input.length > MAX_INPUT_LENGTH) return invalid("empty or too long")
        return parse(input, defaultRegion).flatMap { parsed ->
            val e164 = util.format(parsed, PhoneNumberFormat.E164)
            if (util.isValidNumber(parsed) && PhoneNumber.isE164(e164)) {
                AppResult.Success(PhoneNumber(e164))
            } else {
                invalid("not a valid number")
            }
        }
    }

    /**
     * Parses a message or call sender: alphanumeric headers (`AD-HDFCBK`) and short codes (3–8 digits that
     * are not valid numbers) become [SenderId]; everything else must normalize to a [PhoneNumber].
     */
    fun parseAddress(
        raw: String,
        defaultRegion: String,
    ): AppResult<Address> {
        val input = raw.trim()
        if (input.any(Char::isLetter)) return senderId(input)
        return when (val number = normalize(input, defaultRegion)) {
            is AppResult.Success -> number
            is AppResult.Failure -> if (SHORT_CODE.matches(input)) senderId(input) else number
        }
    }

    /**
     * Reads a telephony handle (the number part of a `tel:` URI) as a [PhoneNumber]: a valid number through
     * [normalize], otherwise any `+` followed by 7–15 digits as it is, so numbers outside assigned ranges still
     * match the user's rules. Returns `null` when the handle is not a phone number.
     */
    fun fromHandle(
        raw: String,
        defaultRegion: String,
    ): PhoneNumber? {
        val cleaned = raw.trim().filterNot { it in VISUAL_SEPARATORS }
        if (cleaned.isEmpty()) return null
        return when (val number = normalize(cleaned, defaultRegion)) {
            is AppResult.Success -> number.value
            is AppResult.Failure -> cleaned.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)
        }
    }

    /**
     * Country of [number] for country rules and allowed countries: its own region, or the main region of its
     * calling code when the number is outside assigned ranges. `null` for non-geographic numbers.
     */
    fun countryOf(number: PhoneNumber): String? =
        regionCode(number) ?: util.getRegionCodeForCountryCode(countryCallingCode(number)).takeIf(::isGeographicRegion)

    /** True when [regionCode] (ISO 3166-1 alpha-2, any case) is a region with a numbering plan. */
    fun isSupportedRegion(regionCode: String): Boolean = regionCode.uppercase() in util.supportedRegions

    /** Line type of [number] (mobile, fixed line, toll-free, …). */
    fun numberType(number: PhoneNumber): LineType = LINE_TYPES[util.getNumberType(parse(number))] ?: LineType.UNKNOWN

    /** ISO 3166-1 alpha-2 region of [number], or `null` for non-geographic numbers. */
    fun regionCode(number: PhoneNumber): String? = util.getRegionCodeForNumber(parse(number))?.takeIf(::isGeographicRegion)

    /** Country calling code of [number], e.g. `880` for Bangladesh. Used to address lookup buckets. */
    fun countryCallingCode(number: PhoneNumber): Int = parse(number).countryCode

    /**
     * True when [number] is in a different country calling code than the SIM's region [simRegion].
     * Numbers that share a calling code (e.g. US and CA on +1) are not foreign. An unknown SIM region
     * returns `false`, so blocking fails open.
     */
    fun isForeign(
        number: PhoneNumber,
        simRegion: String,
    ): Boolean {
        val simCode = util.getCountryCodeForRegion(simRegion.uppercase())
        return simCode != 0 && simCode != countryCallingCode(number)
    }

    private fun isGeographicRegion(region: String): Boolean = region.length == 2 && region != UNKNOWN_REGION

    private fun parse(number: PhoneNumber): LibPhoneNumber = util.parse(number.e164, UNKNOWN_REGION)

    // libphonenumber reports bad input by throwing; map it to a Validation error at this boundary.
    private fun parse(
        input: String,
        region: String,
    ): AppResult<LibPhoneNumber> =
        try {
            AppResult.Success(util.parse(input, region.uppercase()))
        } catch (e: NumberParseException) {
            invalid(e.errorType.name)
        }

    private fun senderId(input: String): AppResult<Address> =
        if (input.isNotEmpty() && input.length <= SenderId.MAX_LENGTH && input.none(Char::isISOControl)) {
            AppResult.Success(SenderId(input))
        } else {
            invalid("not a valid sender id")
        }

    private fun invalid(reason: String): AppResult.Failure = AppResult.Failure(AppError.Validation(reason))

    private companion object {
        const val MAX_INPUT_LENGTH = 64
        const val UNKNOWN_REGION = "ZZ"
        val SHORT_CODE = Regex("""^\d{3,8}$""")
        const val VISUAL_SEPARATORS = " -().\u00A0"
        val LINE_TYPES =
            mapOf(
                PhoneNumberType.FIXED_LINE to LineType.FIXED_LINE,
                PhoneNumberType.MOBILE to LineType.MOBILE,
                PhoneNumberType.FIXED_LINE_OR_MOBILE to LineType.FIXED_LINE_OR_MOBILE,
                PhoneNumberType.TOLL_FREE to LineType.TOLL_FREE,
                PhoneNumberType.PREMIUM_RATE to LineType.PREMIUM_RATE,
                PhoneNumberType.SHARED_COST to LineType.SHARED_COST,
                PhoneNumberType.VOIP to LineType.VOIP,
                PhoneNumberType.PERSONAL_NUMBER to LineType.PERSONAL_NUMBER,
                PhoneNumberType.PAGER to LineType.PAGER,
                PhoneNumberType.UAN to LineType.UAN,
                PhoneNumberType.VOICEMAIL to LineType.VOICEMAIL,
            )
    }
}
