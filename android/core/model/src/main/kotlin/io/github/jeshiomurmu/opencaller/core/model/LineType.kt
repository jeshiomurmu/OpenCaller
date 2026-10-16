package io.github.jeshiomurmu.opencaller.core.model

/** Kind of line a phone number belongs to, as reported by libphonenumber metadata. */
enum class LineType {
    /** Fixed (landline) number. */
    FIXED_LINE,

    /** Mobile number. */
    MOBILE,

    /** The numbering plan cannot tell fixed and mobile apart (e.g. US, CA). */
    FIXED_LINE_OR_MOBILE,

    /** Free-phone number. */
    TOLL_FREE,

    /** Premium-rate number. */
    PREMIUM_RATE,

    /** Cost shared between caller and recipient. */
    SHARED_COST,

    /** VoIP number. */
    VOIP,

    /** Personal number forwarded to another line. */
    PERSONAL_NUMBER,

    /** Pager. */
    PAGER,

    /** Universal access number (companies). */
    UAN,

    /** Voicemail access number. */
    VOICEMAIL,

    /** Type could not be determined. */
    UNKNOWN,
}
