package io.github.jeshiomurmu.opencaller.core.model

/**
 * A phone number in E.164 form (`+` followed by 7–15 digits, no leading zero), e.g. `+8801712345678`.
 *
 * Numbers cross module boundaries only as this type, never as raw strings (docs/rules.md §4). Build one
 * through `PhoneNumberNormalizer` (`core:phonenumber`); the constructor only checks the E.164 shape.
 */
@JvmInline
value class PhoneNumber(
    val e164: String,
) : Address {
    init {
        require(E164.matches(e164)) { "Not an E.164 number" }
    }

    /** Never prints the number itself, so a [PhoneNumber] can't leak into logs by accident. */
    override fun toString(): String = "PhoneNumber(+****${e164.takeLast(VISIBLE_DIGITS)})"

    /** E.164 shape checks shared with callers. */
    companion object {
        private const val VISIBLE_DIGITS = 4
        private val E164 = Regex("""^\+[1-9]\d{6,14}$""")

        /** True when [value] has the E.164 shape accepted by [PhoneNumber]. */
        fun isE164(value: String): Boolean = E164.matches(value)
    }
}
