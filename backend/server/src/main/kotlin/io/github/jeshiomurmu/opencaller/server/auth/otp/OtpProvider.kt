package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.config.Environment
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/** Delivers a one-time code to a phone (docs/architecture.md §4.7). The server generates and checks the code. */
interface OtpProvider {
    /** Sends [code] to [number]; it expires after [validFor]. Throws [OtpDeliveryException] if it can't be sent. */
    suspend fun send(
        number: E164,
        code: String,
        validFor: Duration,
    )
}

/** The provider refused or couldn't be reached; the code was not delivered. */
class OtpDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The text of the SMS. */
internal fun otpMessage(
    code: String,
    validFor: Duration,
): String = "Your OpenCaller code is $code. It expires in ${validFor.inWholeMinutes} minutes. Don't share it with anyone."

/**
 * Development only: writes the code to the server log instead of sending it. Refuses to exist when the server
 * runs in production (the config check refuses `OTP_PROVIDER=log` there too).
 */
class LogOtpProvider(
    environment: Environment,
) : OtpProvider {
    init {
        require(environment != Environment.PRODUCTION) { "LogOtpProvider is for development only and can't run in production" }
    }

    override suspend fun send(
        number: E164,
        code: String,
        validFor: Duration,
    ) {
        // The number is redacted to its last digits by the JSON log encoder; the 6-digit code is not a phone number.
        log.warn("Development OTP for {}: {} (valid {} min, not sent by SMS)", number, code, validFor.inWholeMinutes)
    }

    private companion object {
        val log = LoggerFactory.getLogger(LogOtpProvider::class.java)
    }
}
