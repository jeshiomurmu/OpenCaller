package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.ml.classify.ClassifierInput
import io.github.jeshiomurmu.opencaller.core.ml.classify.SmsClassifier
import io.github.jeshiomurmu.opencaller.core.ml.link.LinkChecker
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtraction
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtractor
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionParser
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import javax.inject.Inject

/**
 * What the device made of a message (docs/architecture.md §4.4 steps 3–5): its category with the classifier's
 * [score], the one-time code if it is one, and the link risks. [imitates] names a brand a link copies.
 */
data class MessageAnalysis(
    val category: MessageCategory,
    val score: Float,
    val otp: OtpExtraction? = null,
    val risks: Set<MessageRisk> = emptySet(),
    val imitates: String? = null,
) {
    /** The fallback when analysis fails or runs out of time: Personal, nothing found (docs/rules.md §5). */
    companion object {
        /** A Personal message with no findings. */
        val PERSONAL = MessageAnalysis(MessageCategory.PERSONAL, score = 0f)
    }
}

/** Runs `core:ml` on a received SMS: OTP and transaction extraction, the link check and the classifier. */
class MessageAnalyzer
    @Inject
    constructor(
        private val otp: OtpExtractor,
        private val transactions: TransactionParser,
        private val links: LinkChecker,
        private val classifier: SmsClassifier,
    ) {
        /** The code and the transaction in [text] (fast: regular expressions only), which the sender resolver uses too. */
        fun hints(
            text: String,
            sender: Address?,
        ): TextHints = TextHints(otp.extract(text), transactions.parse(text, sender))

        /** The category of [text] from [sender], with what [hints] found. */
        suspend fun analyze(
            text: String,
            sender: ResolvedSender,
            hints: TextHints,
        ): MessageAnalysis {
            val report = links.check(text)
            val result = classifier.classify(ClassifierInput(text, sender.address, hints.otp, hints.transaction, report))
            return MessageAnalysis(
                category = result.category,
                score = result.score,
                otp = hints.otp.takeIf { result.category == MessageCategory.OTP },
                risks = report.risks,
                imitates = report.imitates,
            )
        }
    }
