package io.github.jeshiomurmu.opencaller.core.ml.classify

import io.github.jeshiomurmu.opencaller.core.ml.sender.HeaderType
import io.github.jeshiomurmu.opencaller.core.ml.sender.SenderHeader
import io.github.jeshiomurmu.opencaller.core.ml.text.SmsText
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import javax.inject.Inject

/**
 * The rules-only classifier (no trained model ships yet: `ml/sms-classifier/README.md`). In order: phishing (a
 * dangerous link, or a doubtful link with an account threat), one-time code, spam, bill, transaction, delivery,
 * promotion; anything else is Personal. Scores are fixed per rule, not probabilities.
 */
class RuleSmsClassifier
    @Inject
    constructor() : SmsClassifier {
        override fun classify(input: ClassifierInput): Classification {
            val text = SmsText.withoutLinks(SmsText.asciiDigits(input.text))
            val category =
                when {
                    isPhishing(input, text) -> MessageCategory.PHISHING
                    input.otp != null -> MessageCategory.OTP
                    CategoryWords.SPAM.foundIn(text) -> MessageCategory.SPAM
                    CategoryWords.BILL.foundIn(text) -> MessageCategory.BILL
                    input.transaction != null -> MessageCategory.TRANSACTION
                    CategoryWords.DELIVERY.foundIn(text) -> MessageCategory.DELIVERY
                    CategoryWords.PROMOTION.foundIn(text) || isPromotionalHeader(input) -> MessageCategory.PROMOTION
                    else -> MessageCategory.PERSONAL
                }
            return Classification(category, SCORES.getValue(category))
        }

        // From a phone number, an account threat with any link is enough; from a business, the link must be doubtful too.
        private fun isPhishing(
            input: ClassifierInput,
            text: String,
        ): Boolean {
            val links = input.links
            val urgent = CategoryWords.URGENT.foundIn(text)
            val fromPerson = input.sender is PhoneNumber
            return links.isDangerous ||
                (links.isDoubtful && (urgent || fromPerson)) ||
                (urgent && fromPerson && links.links.isNotEmpty())
        }

        private fun isPromotionalHeader(input: ClassifierInput): Boolean =
            (input.sender as? SenderId)?.let { SenderHeader.parseIndian(it.value) }?.type == HeaderType.PROMOTIONAL

        private companion object {
            val SCORES =
                mapOf(
                    MessageCategory.PHISHING to 0.9f,
                    MessageCategory.OTP to 0.95f,
                    MessageCategory.SPAM to 0.8f,
                    MessageCategory.BILL to 0.8f,
                    MessageCategory.TRANSACTION to 0.9f,
                    MessageCategory.DELIVERY to 0.8f,
                    MessageCategory.PROMOTION to 0.7f,
                    MessageCategory.PERSONAL to 0.5f,
                )
        }
    }
