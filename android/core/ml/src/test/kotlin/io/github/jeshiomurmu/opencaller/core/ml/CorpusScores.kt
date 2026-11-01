package io.github.jeshiomurmu.opencaller.core.ml

import io.github.jeshiomurmu.opencaller.core.ml.classify.ClassifierInput
import io.github.jeshiomurmu.opencaller.core.ml.classify.RuleSmsClassifier
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtractor
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionInfo
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.testing.CorpusMessage

/** Scores of the extractors and the classifier on one corpus split. */
data class SplitScores(
    val split: String,
    val messages: Int,
    val otp: OtpScores,
    val transactions: TransactionScores,
    val classes: Map<MessageCategory, ClassScore>,
    val misses: List<String>,
) {
    /** Unweighted mean of the per-class F1 over the classes in the split. */
    val macroF1: Double get() = classes.values.map { it.f1 }.average()
}

/** OTP extraction: a found code counts only when it is exactly the expected one. */
data class OtpScores(
    val expected: Int,
    val found: Int,
    val correct: Int,
    val origins: Pair<Int, Int>,
    val appHashes: Pair<Int, Int>,
    val expiries: Pair<Int, Int>,
) {
    val precision: Double get() = if (found == 0) 1.0 else correct.toDouble() / found
    val recall: Double get() = if (expected == 0) 1.0 else correct.toDouble() / expected
}

/** Transaction messages whose field matched (a missing field must be missing). */
data class TransactionScores(
    val messages: Int,
    val amount: Int,
    val currency: Int,
    val last4: Int,
    val merchant: Int,
)

/** One class's counts. */
data class ClassScore(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
) {
    val f1: Double get() = if (truePositives == 0) 0.0 else 2.0 * truePositives / (2 * truePositives + falsePositives + falseNegatives)
}

/** Runs the extractors and the classifier over corpus messages. */
object CorpusScoring {
    private val extractor = OtpExtractor()
    private val parser = TestMl.transactionParser()
    private val links = TestMl.linkChecker()
    private val classifier = RuleSmsClassifier()

    /** The scores of [messages] (split [split]). */
    suspend fun score(
        split: String,
        messages: List<CorpusMessage>,
    ): SplitScores {
        val results = messages.map { it to run(it) }
        val misses = results.filter { (m, r) -> r.category.name != m.category || r.code != m.otp }.map { (m, r) -> "${m.id}→${r.category}" }
        return SplitScores(split, messages.size, otpScores(results), transactionScores(results), classScores(results), misses)
    }

    private data class Result(
        val code: String?,
        val origin: String?,
        val appHash: String?,
        val expiry: Int?,
        val transaction: TransactionInfo?,
        val category: MessageCategory,
    )

    private suspend fun run(message: CorpusMessage): Result {
        val otp = extractor.extract(message.text)
        val transaction = parser.parse(message.text, message.address)
        val report = links.check(message.text)
        val category = classifier.classify(ClassifierInput(message.text, message.address, otp, transaction, report)).category
        return Result(otp?.code, otp?.origin, otp?.appHash, otp?.validForMinutes, transaction, category)
    }

    private fun otpScores(results: List<Pair<CorpusMessage, Result>>): OtpScores {
        fun field(
            expected: (CorpusMessage) -> Any?,
            actual: (Result) -> Any?,
        ): Pair<Int, Int> {
            val withField = results.filter { expected(it.first) != null }
            return withField.count { (m, r) -> expected(m) == actual(r) } to withField.size
        }
        return OtpScores(
            expected = results.count { it.first.otp != null },
            found = results.count { it.second.code != null },
            correct = results.count { (m, r) -> r.code != null && r.code == m.otp },
            origins = field({ it.origin }, { it.origin }),
            appHashes = field({ it.appHash }, { it.appHash }),
            expiries = field({ it.expiryMinutes }, { it.expiry }),
        )
    }

    private fun transactionScores(results: List<Pair<CorpusMessage, Result>>): TransactionScores {
        val transactions = results.filter { it.first.category == MessageCategory.TRANSACTION.name }

        fun count(matches: (CorpusMessage, TransactionInfo?) -> Boolean) = transactions.count { (m, r) -> matches(m, r.transaction) }
        return TransactionScores(
            messages = transactions.size,
            amount = count { m, t -> t?.amount?.toPlainString() == m.amount },
            currency = count { m, t -> t?.currency == m.currency },
            last4 = count { m, t -> t != null && t.last4 == m.last4 },
            merchant = count { m, t -> t != null && t.merchant == m.merchant },
        )
    }

    private fun classScores(results: List<Pair<CorpusMessage, Result>>): Map<MessageCategory, ClassScore> =
        MessageCategory.entries
            .filter { category -> results.any { it.first.category == category.name } }
            .associateWith { category ->
                ClassScore(
                    truePositives = results.count { (m, r) -> m.category == category.name && r.category == category },
                    falsePositives = results.count { (m, r) -> m.category != category.name && r.category == category },
                    falseNegatives = results.count { (m, r) -> m.category == category.name && r.category != category },
                )
            }
}
