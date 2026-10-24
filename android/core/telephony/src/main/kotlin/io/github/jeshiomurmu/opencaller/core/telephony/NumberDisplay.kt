package io.github.jeshiomurmu.opencaller.core.telephony

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import javax.inject.Inject

/**
 * Phone numbers as the screens show them (docs/design.md §7): national format for the user's country (SIM, then
 * network, then locale), international otherwise.
 */
class NumberDisplay
    @Inject
    constructor(
        private val normalizer: PhoneNumberNormalizer,
        private val region: HomeRegionProvider,
    ) {
        /** The user's country (ISO 3166-1 alpha-2), or `ZZ` (unknown) when the phone can't tell. */
        fun homeRegion(): String = region.homeRegion() ?: UNKNOWN_REGION

        /** [number] for display, e.g. `(201) 555-0123` in the US and `+1 201-555-0123` elsewhere. */
        fun format(
            number: PhoneNumber,
            home: String = homeRegion(),
        ): String = normalizer.format(number, home)

        /** [input] from the dial pad, formatted as typed; codes and pauses stay as they are. */
        fun asYouType(
            input: String,
            home: String = homeRegion(),
        ): String = normalizer.formatAsYouType(input, home)

        /** [number] with its middle hidden, e.g. `+880 ••• 1234`. */
        fun masked(number: PhoneNumber): String = normalizer.masked(number)

        private companion object {
            const val UNKNOWN_REGION = "ZZ"
        }
    }
