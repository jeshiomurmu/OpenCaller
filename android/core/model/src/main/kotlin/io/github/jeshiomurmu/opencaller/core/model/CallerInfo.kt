package io.github.jeshiomurmu.opencaller.core.model

/**
 * Everything known about a caller, as shown on the caller card.
 *
 * @property number the caller's number, or `null` for a hidden caller.
 * @property displayName name to show, or `null` if none is known.
 * @property nameSource where [displayName] came from; `null` when there is no name.
 * @property spamVerdict spam assessment, or `null` if not checked.
 * @property verifiedBusinessId id of the verified business, or `null` if the caller is not a verified business.
 * @property logoUrl business logo URL, if any.
 * @property callReason business-provided reason for the call, if any.
 * @property lineType kind of line, if known.
 * @property regionCode ISO 3166-1 alpha-2 region of the number, if known.
 * @property verification the carrier's STIR/SHAKEN verdict for the call (Android 11+).
 */
data class CallerInfo(
    val number: PhoneNumber?,
    val displayName: String? = null,
    val nameSource: NameSource? = null,
    val spamVerdict: SpamVerdict? = null,
    val verifiedBusinessId: String? = null,
    val logoUrl: String? = null,
    val callReason: String? = null,
    val lineType: LineType? = null,
    val regionCode: String? = null,
    val verification: CallerVerification = CallerVerification.UNKNOWN,
) {
    /** True when the caller withheld their number. */
    val isHidden: Boolean get() = number == null

    /** True when the caller is a verified business. */
    val isVerifiedBusiness: Boolean get() = verifiedBusinessId != null
}
