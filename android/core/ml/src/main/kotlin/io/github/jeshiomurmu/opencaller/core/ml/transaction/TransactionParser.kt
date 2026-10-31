package io.github.jeshiomurmu.opencaller.core.ml.transaction

import io.github.jeshiomurmu.opencaller.core.ml.sender.SenderHeader
import io.github.jeshiomurmu.opencaller.core.ml.text.SmsText
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * A bank or wallet transaction read from an SMS on the device: [amount] with 2 decimals in [currency] (ISO 4217),
 * the card or account's [last4] digits, the [merchant] or payee, and the [bank] whose template matched (`null` when
 * only the generic rules did).
 */
data class TransactionInfo(
    val amount: BigDecimal,
    val currency: String,
    val direction: Direction,
    val last4: String? = null,
    val merchant: String? = null,
    val bank: String? = null,
) {
    /** Never prints the merchant or the card digits. */
    override fun toString(): String = "TransactionInfo(currency=$currency, direction=$direction, bank=$bank)"
}

/**
 * Reads amount, currency, card digits and merchant from transaction alerts: first the templates of the bank that
 * sent the message (`assets/ml/banks.json`), then every bank's templates, then generic rules. The generic rules
 * skip messages from phone numbers, so "I sent you $20" from a friend is not a transaction.
 */
class TransactionParser
    @Inject
    constructor(
        private val templates: BankTemplates,
    ) {
        /** The transaction in [text] from [sender], or `null`. */
        fun parse(
            text: String,
            sender: Address?,
        ): TransactionInfo? {
            val ascii = SmsText.asciiDigits(SmsText.withoutLinks(text))
            val key = senderKey(sender)
            val banks = templates.banks.sortedByDescending { key != null && key in it.senders }
            return banks.firstNotNullOfOrNull { bank -> matchBank(bank, ascii) } ?: GenericTransaction.parse(ascii, sender)
        }

        private fun matchBank(
            bank: Bank,
            text: String,
        ): TransactionInfo? =
            bank.templates.firstNotNullOfOrNull { template ->
                val match = template.pattern.find(text)
                match?.let { group(it, template, "amount") }?.let { amount ->
                    TransactionInfo(
                        amount = amountOf(amount),
                        currency = bank.currency,
                        direction = template.direction,
                        last4 = group(match, template, "last4"),
                        merchant = group(match, template, "merchant")?.trim(),
                        bank = bank.name,
                    )
                }
            }

        // A template names only the groups its message has; asking for another one would throw.
        private fun group(
            match: MatchResult,
            template: BankTemplate,
            name: String,
        ): String? = if ("(?<$name>" in template.pattern.pattern) match.groups[name]?.value else null

        private fun senderKey(sender: Address?): String? =
            when (sender) {
                is SenderId -> (SenderHeader.parseIndian(sender.value)?.header ?: sender.value).lowercase()
                is PhoneNumber, null -> null
            }
    }

/** "1,25,000.5" → 125000.50. */
internal fun amountOf(text: String): BigDecimal = BigDecimal(text.replace(",", "")).setScale(2, RoundingMode.HALF_UP)

/** Amount after a currency, a direction word, card digits and "at <merchant>", for banks without templates. */
internal object GenericTransaction {
    private val AMOUNT = Regex("""(?i)(rs\.?|inr|₹|usd|\$|bdt|tk\.?|৳|रु\.?)\s?(\d[\d,]*(?:\.\d{1,2})?)""")
    private val DEBIT = Regex("""(?i)\b(?:debited|spent|purchase|paid|payment of|sent|cash out|withdrawn)\b|डेबिट|খরচ""")
    private val CREDIT = Regex("""(?i)\b(?:credited|received|deposit(?:ed)?)\b|क्रेडिट|জমা""")
    private val LAST4 = Regex("""(?i)(?:ending(?: in)?|a/c(?: no\.?)?|acct|account|card)\s*[x*]*(\d{4})\b|[x*]{2,}(\d{4})\b""")
    private val MERCHANT = Regex("""(?i)\bat ([^.\n]+?)(?: on |\.|$)""")
    private val CURRENCIES =
        mapOf(
            "rs" to "INR",
            "inr" to "INR",
            "₹" to "INR",
            "रु" to "INR",
            "usd" to "USD",
            "$" to "USD",
            "bdt" to "BDT",
            "tk" to "BDT",
            "৳" to "BDT",
        )

    fun parse(
        text: String,
        sender: Address?,
    ): TransactionInfo? {
        val amount = AMOUNT.find(text)
        val direction =
            when {
                CREDIT.containsMatchIn(text) -> Direction.CREDIT
                DEBIT.containsMatchIn(text) -> Direction.DEBIT
                else -> null
            }
        if (sender is PhoneNumber || amount == null || direction == null) return null
        val last4 =
            LAST4
                .find(text)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull(String::isNotEmpty)
        return TransactionInfo(
            amount = amountOf(amount.groupValues[2]),
            currency = CURRENCIES.getValue(amount.groupValues[1].lowercase().trimEnd('.')),
            direction = direction,
            last4 = last4,
            merchant =
                MERCHANT
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.trim(),
        )
    }
}
