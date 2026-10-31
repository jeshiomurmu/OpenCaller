package io.github.jeshiomurmu.opencaller.core.ml.classify

import io.github.jeshiomurmu.opencaller.core.ml.link.LinkReport
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtraction
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionInfo
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory

/**
 * A received SMS and what the earlier pipeline steps found in it (docs/architecture.md §4.4 steps 3 and 5), so the
 * classifier doesn't repeat them.
 */
data class ClassifierInput(
    val text: String,
    val sender: Address?,
    val otp: OtpExtraction? = null,
    val transaction: TransactionInfo? = null,
    val links: LinkReport = LinkReport(),
) {
    /** Never prints the text. */
    override fun toString(): String = "ClassifierInput(length=${text.length}, otp=${otp != null}, links=${links.links.size})"
}

/** The smart-inbox [category] and how sure the classifier is (0–1). */
data class Classification(
    val category: MessageCategory,
    val score: Float,
)

/**
 * Sorts an SMS into a smart-inbox category on the device (docs/architecture.md §4.4 step 4). The app uses
 * [RuleSmsClassifier]; a LiteRT model (`ml/sms-classifier`) can take its place once one is trained on real messages.
 */
fun interface SmsClassifier {
    /** The category of [input]. */
    fun classify(input: ClassifierInput): Classification
}
