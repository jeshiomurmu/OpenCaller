package io.github.jeshiomurmu.opencaller.core.ml.classify

/**
 * The words the rules look for, per category, in English, Bengali and Hindi. English is matched by word
 * (case-insensitive regular expressions); Bengali and Hindi by substring.
 */
internal class CategoryWords(
    english: String,
    private val others: List<String>,
) {
    private val pattern = Regex("(?i)$english")

    /** True when [text] has one of the words. */
    fun foundIn(text: String): Boolean = pattern.containsMatchIn(text) || others.any(text::contains)

    companion object {
        /** Account threats and requests for credentials or money (phishing). */
        val URGENT =
            CategoryWords(
                """\b(?:verify|suspended|blocked|locked|kyc|pan|pin|password|update your|reactivate|unclaimed|refund|""" +
                    """redelivery|fee|disconnected|will be closed|submit your details|card number)\b""",
                listOf("बंद हो", "केवाईसी", "সাসপেন্ড", "বন্ধ হয়ে", "যাচাই করুন"),
            )

        /** Prizes, loans and get-rich offers (spam). */
        val SPAM =
            CategoryWords(
                """\b(?:congratulations|you (?:have )?won|winner|lottery|lucky draw|prize|gift card|pre-approved|""" +
                    """loan|no credit check|earn\b.{0,30}\b(?:daily|from home)|work(?:ing)? (?:online )?from home|""" +
                    """part-time job|lose \d+ ?lbs|one trick)\b""",
                listOf("लॉटरी", "जीती", "इनाम", "बधाई हो", "অভিনন্দন", "জিতেছেন", "পুরস্কার", "লটারি"),
            )

        /** Bills and payment reminders. */
        val BILL =
            CategoryWords(
                """\b(?:bill|bills|is due|due on|due date|amount due|last date of payment|statement is ready|late fee)\b""",
                listOf("बिल", "देय तिथि", "বিল", "পরিশোধের"),
            )

        /** Orders and parcels on their way. */
        val DELIVERY =
            CategoryWords(
                """\b(?:shipped|shipment|out for delivery|delivered|parcel|package|courier|awb|tracking|on its way|picked up)\b""",
                listOf("भेज दिया", "डिलीवरी", "पार्सल", "পার্সেল", "ডেলিভারি", "রাইডার"),
            )

        /** Sales, offers and plans to buy. */
        val PROMOTION =
            CategoryWords(
                """(?:\d+ ?% (?:off|cashback)|\b(?:sale|offers?|discount|cashback|use code|shop now|buy 1 get 1|t&c|""" +
                    """reply stop|txt stop|recharge|download the app|hurry)\b|\*\d+(?:\*\d+)*#)""",
                listOf("कैशबैक", "रिचार्ज", "ऑफ़र", "छूट", "सिर्फ आज", "অফার", "ছাড়", "ডায়াল"),
            )
    }
}
