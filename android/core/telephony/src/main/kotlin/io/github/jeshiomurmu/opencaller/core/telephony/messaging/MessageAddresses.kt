package io.github.jeshiomurmu.opencaller.core.telephony.messaging

/**
 * Compares and picks the addresses of a conversation. Android stores addresses as they came (`+1 555-000-1111`,
 * `5550001111`, `ALERTS`), so two are the same person when their last [MATCH_DIGITS] digits match, or when the
 * non-numeric ones are equal ignoring case.
 */
object MessageAddresses {
    /** The From placeholder Android stores for the user's own sent MMS (`insert-address-token`). */
    const val INSERT_ADDRESS_TOKEN = "insert-address-token"

    /** True when [a] and [b] name the same sender or recipient. */
    fun same(
        a: String,
        b: String,
    ): Boolean {
        val digitsA = a.filter(Char::isDigit)
        val digitsB = b.filter(Char::isDigit)
        val numeric = digitsA.length >= MIN_NUMBER_DIGITS && digitsB.length >= MIN_NUMBER_DIGITS && '@' !in a && '@' !in b
        return if (numeric) {
            digitsA.takeLast(
                MATCH_DIGITS,
            ) == digitsB.takeLast(MATCH_DIGITS)
        } else {
            a.trim().equals(b.trim(), ignoreCase = true)
        }
    }

    /**
     * The other people of a received MMS: the sender and every recipient except the user. [self] holds the user's own
     * numbers when Android knows them. Without them a message with a single recipient was sent to the user alone;
     * with several, all are kept (the user may then appear in the group: docs/phase.md issue list).
     */
    fun othersInReceived(
        from: String?,
        to: List<String>,
        cc: List<String>,
        self: Collection<String>,
    ): List<String> {
        val recipients = (to + cc).filterNot { isPlaceholder(it) }
        val withoutSelf = recipients.filterNot { address -> self.any { same(it, address) } }
        val others =
            when {
                withoutSelf.size < recipients.size -> withoutSelf
                recipients.size == 1 -> emptyList()
                else -> recipients
            }
        return distinct(listOfNotNull(from?.takeUnless(::isPlaceholder)) + others).ifEmpty { recipients.take(1) }
    }

    /** [addresses] with each person once, in their first order. */
    fun distinct(addresses: List<String>): List<String> =
        addresses.fold(mutableListOf()) { kept, address ->
            if (address.isNotBlank() && kept.none { same(it, address) }) kept += address.trim()
            kept
        }

    private fun isPlaceholder(address: String): Boolean = address.isBlank() || address.equals(INSERT_ADDRESS_TOKEN, ignoreCase = true)

    /** Digits compared: enough for a national number, short enough to ignore the country code. */
    const val MATCH_DIGITS = 7

    private const val MIN_NUMBER_DIGITS = 5
}
