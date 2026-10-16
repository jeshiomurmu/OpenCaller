package io.github.jeshiomurmu.opencaller.server.auth

import com.google.i18n.phonenumbers.PhoneNumberUtil

/** A validated phone number in canonical E.164 form, e.g. `+8801712345678`. */
@JvmInline
value class E164 private constructor(
    val value: String,
) {
    override fun toString() = value

    /** Parsing and validation (docs/rules.md §7: E.164 via libphonenumber on the server too). */
    companion object {
        private val util = PhoneNumberUtil.getInstance()

        // "+" then 7..15 digits, the first not 0 (ITU-T E.164 allows at most 15 digits).
        private val shape = Regex("""\+[1-9]\d{6,14}""")

        /** Returns the number if [raw] is a valid number written exactly in E.164, else `null`. */
        fun parseOrNull(raw: String?): E164? {
            // libphonenumber's parse throws only NumberParseException ("not a phone number"): invalid input, a 400.
            val number = raw?.takeIf(shape::matches)?.let { runCatching { util.parse(it, null) }.getOrNull() } ?: return null
            val canonical = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            return if (util.isValidNumber(number) && canonical == raw) E164(canonical) else null
        }
    }
}
