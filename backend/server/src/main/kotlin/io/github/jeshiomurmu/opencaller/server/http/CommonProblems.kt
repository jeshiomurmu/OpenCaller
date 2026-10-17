package io.github.jeshiomurmu.opencaller.server.http

import io.github.jeshiomurmu.opencaller.server.redis.RateDecision
import io.ktor.http.HttpStatusCode

/** Throws 429 `rate_limited` with `Retry-After` if [decision] refused the request. */
fun enforce(
    decision: RateDecision,
    title: String,
) {
    if (decision is RateDecision.Limited) {
        throw ApiProblem(
            HttpStatusCode.TooManyRequests,
            "rate_limited",
            title,
            "Try again in ${decision.retryAfterSeconds} seconds.",
            retryAfterSeconds = decision.retryAfterSeconds,
        )
    }
}

/** Throws [problem] unless [condition] holds. */
inline fun requireValid(
    condition: Boolean,
    problem: () -> ApiProblem,
) {
    if (!condition) throw problem()
}

/** `true` if [text] has 1 to [maxLength] characters and no control characters other than line breaks. */
fun isPlainText(
    text: String,
    maxLength: Int,
): Boolean = text.length in 1..maxLength && text.none { it.isISOControl() && it != '\n' }

/** 400 `invalid_phone_number`. */
fun invalidNumber(detail: String = "Send the number in E.164 format, e.g. +14155550123.") =
    ApiProblem(HttpStatusCode.BadRequest, "invalid_phone_number", "Not a valid phone number", detail)
