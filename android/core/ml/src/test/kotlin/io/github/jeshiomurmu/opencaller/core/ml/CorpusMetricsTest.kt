package io.github.jeshiomurmu.opencaller.core.ml

import io.github.jeshiomurmu.opencaller.core.testing.SmsCorpus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * TC-OTP-001 and TC-SMS-004: OTP extraction precision and recall, transaction field accuracy and the classifier's
 * macro-F1 on every corpus split, printed and written to `build/reports/corpus-metrics/` (`./gradlew
 * :core:ml:corpusMetrics`). The gates apply to the held-out test split. The corpus is generated, so the scores are
 * optimistic (ml/sms-classifier/README.md).
 */
class CorpusMetricsTest {
    @Test
    fun corpusScoresMeetTheExitCriteria() =
        runTest {
            val scores = SmsCorpus.SPLITS.map { CorpusScoring.score(it, SmsCorpus.load(it)) }
            val report = scores.joinToString("\n\n", transform = ::describe)
            println(report)
            System.getProperty("opencaller.ml.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(report + "\n") }

            val test = scores.single { it.split == "test" }
            assertTrue("OTP precision ${test.otp.precision} < $MIN_PRECISION", test.otp.precision >= MIN_PRECISION)
            assertTrue("OTP recall ${test.otp.recall} < $MIN_RECALL", test.otp.recall >= MIN_RECALL)
            assertTrue("macro-F1 ${test.macroF1} < $MIN_MACRO_F1", test.macroF1 >= MIN_MACRO_F1)
        }

    private fun describe(s: SplitScores): String =
        buildString {
            val otp = s.otp
            val txn = s.transactions
            appendLine("Corpus split ${s.split}: ${s.messages} messages")
            appendLine(
                "  OTP: ${otp.expected} codes, ${otp.found} found, ${otp.correct} correct; precision ${pct(
                    otp.precision,
                )}, recall ${pct(otp.recall)}",
            )
            appendLine("  OTP fields: origin ${ratio(otp.origins)}, app hash ${ratio(otp.appHashes)}, expiry ${ratio(otp.expiries)}")
            appendLine(
                "  Transactions (${txn.messages}): amount ${txn.amount}, currency ${txn.currency}, " +
                    "last 4 ${txn.last4}, merchant ${txn.merchant}",
            )
            s.classes.forEach { (category, c) ->
                appendLine(
                    "  ${category.name.padEnd(
                        PAD,
                    )} F1 ${pct(c.f1)} (tp ${c.truePositives}, fp ${c.falsePositives}, fn ${c.falseNegatives})",
                )
            }
            appendLine("  Classifier macro-F1: ${pct(s.macroF1)}")
            append("  Misses: ${s.misses.ifEmpty { listOf("none") }.joinToString(", ")}")
        }

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * HUNDRED)

    private fun ratio(pair: Pair<Int, Int>): String = "${pair.first}/${pair.second}"

    private companion object {
        const val MIN_PRECISION = 0.98
        const val MIN_RECALL = 0.95

        // A floor against regressions; the corpus being generated, real-message F1 will be lower.
        const val MIN_MACRO_F1 = 0.90
        const val HUNDRED = 100
        const val PAD = 12
    }
}
