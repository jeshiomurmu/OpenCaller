package io.github.jeshiomurmu.opencaller.core.ml.otp

import io.github.jeshiomurmu.opencaller.core.ml.text.SmsText
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.OtpInfo
import javax.inject.Inject

/**
 * What [OtpExtractor] found in a message. [validForMinutes] comes from a phrase like "valid for 10 minutes".
 * [origin] is the site an origin-bound code is meant for (`@example.com #123456`).
 */
data class OtpExtraction(
    val code: String,
    val origin: String? = null,
    val appHash: String? = null,
    val validForMinutes: Int? = null,
) {
    /** The code as stored with the message, expiring [validForMinutes] after [receivedAtMillis]. */
    fun toOtpInfo(
        sender: Address?,
        receivedAtMillis: Long,
    ): OtpInfo =
        OtpInfo(
            code = code,
            sender = sender,
            origin = origin,
            expiresAtMillis = validForMinutes?.let { receivedAtMillis + it * MILLIS_PER_MINUTE },
            appHash = appHash,
        )

    /** Never prints the code. */
    override fun toString(): String = "OtpExtraction(code=****, origin=$origin, validForMinutes=$validForMinutes)"

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Finds the one-time code in an SMS (docs/architecture.md §4.4 step 3), on the device only: 4–8 digit codes near a
 * keyword (English, Bengali, Hindi; digits in any script), the origin-bound last line (`@domain #code`), the SMS
 * Retriever app hash and how long the code is valid. Returns `null` for a message without a code.
 */
class OtpExtractor
    @Inject
    constructor() {
        /** The code in [text], or `null`. */
        fun extract(text: String): OtpExtraction? {
            val ascii = SmsText.asciiDigits(text).trimEnd()
            val bound = OriginBoundCode.parse(ascii)
            val hash = if (bound == null) AppHash.find(ascii) else null
            val body =
                SmsText.withoutLinks(
                    when {
                        bound != null -> ascii.substringBeforeLast('\n')
                        hash != null -> ascii.removeSuffix(hash)
                        else -> ascii
                    },
                )
            val code = bound?.code ?: nearestToKeyword(body, hashMarked = hash != null)
            return code?.let { OtpExtraction(code = it, origin = bound?.origin, appHash = hash, validForMinutes = ValidFor.minutes(body)) }
        }

        // The candidate closest to a keyword; a message with an app hash needs no keyword.
        private fun nearestToKeyword(
            body: String,
            hashMarked: Boolean,
        ): String? {
            val keywords = OtpKeywords.find(body)
            val candidates = CodeCandidates.find(body)
            if (keywords.isEmpty()) return if (hashMarked) candidates.firstOrNull()?.let(body::substring) else null
            return candidates.minByOrNull { candidate -> keywords.minOf { distance(it, candidate) } }?.let(body::substring)
        }

        private fun distance(
            a: IntRange,
            b: IntRange,
        ): Int =
            when {
                a.last < b.first -> b.first - a.last
                b.last < a.first -> a.first - b.last
                else -> 0
            }
    }

/** "Valid for 10 minutes", "expires in 5 min", "১০ মিনিট", "10 मिनट", "1 hour". */
internal object ValidFor {
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_MINUTES = 24 * MINUTES_PER_HOUR
    private val PHRASE =
        Regex("""(?i)(\d{1,4})\s*-?\s*(minutes?(?![a-z])|mins?(?![a-z])|मिनट|মিনিট|hours?(?![a-z])|hrs?(?![a-z])|घंटे|घंटा|ঘণ্টা)""")
    private val HOUR_WORDS = setOf("hour", "hours", "hr", "hrs", "घंटे", "घंटा", "ঘণ্টা")

    /** Minutes stated in [text] (ASCII digits), or `null`. */
    fun minutes(text: String): Int? {
        val match = PHRASE.find(text) ?: return null
        val value = match.groupValues[1].toInt()
        val minutes = if (match.groupValues[2].lowercase() in HOUR_WORDS) value * MINUTES_PER_HOUR else value
        return minutes.takeIf { it in 1..MAX_MINUTES }
    }
}
