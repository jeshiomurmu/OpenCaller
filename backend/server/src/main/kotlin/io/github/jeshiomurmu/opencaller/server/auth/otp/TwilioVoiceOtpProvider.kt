package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.config.TwilioConfig
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Reads a code aloud in a phone call, for business numbers that can't receive SMS (landlines). Written against
 * Twilio's official API definition (twilio-oai `twilio_api_v2010.json`, operation `CreateCall`):
 * `POST {baseUrl}/2010-04-01/Accounts/{AccountSid}/Calls.json`, HTTP Basic auth, form-encoded `To`, `From` (a Twilio
 * number, [TwilioConfig.voiceFrom]), `Twiml` (≤ 4,000 characters) and `Timeout` (seconds to ring); success is
 * `201 Created`. The TwiML is a `Response` with a `Say` verb (`loop` attribute) as in Twilio's helper library
 * (`com.twilio.twiml.voice.Say`).
 */
class TwilioVoiceOtpProvider(
    private val config: TwilioConfig,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) : OtpProvider {
    private val from = requireNotNull(config.voiceFrom) { "TWILIO_VOICE_FROM is not set" }
    private val endpoint = URI.create("${config.baseUrl.trimEnd('/')}/2010-04-01/Accounts/${config.accountSid}/Calls.json")
    private val authorization =
        "Basic " + Base64.getEncoder().encodeToString("${config.accountSid}:${config.authToken}".toByteArray(Charsets.UTF_8))

    override suspend fun send(
        number: E164,
        code: String,
        validFor: Duration,
    ) {
        val form =
            listOf(
                "To" to number.value,
                "From" to from,
                "Twiml" to voiceTwiml(code),
                "Timeout" to RING_SECONDS.toString(),
            ).joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(TIMEOUT.toJavaDuration())
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response =
            try {
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: IOException) {
                throw OtpDeliveryException("Twilio could not be reached", e)
            }
        if (response.statusCode() != CREATED) {
            log.warn("Twilio refused the call: HTTP {}", response.statusCode())
            throw OtpDeliveryException("Twilio returned HTTP ${response.statusCode()}")
        }
    }

    private companion object {
        const val CREATED = 201
        const val RING_SECONDS = 30
        val TIMEOUT = 10.seconds
        val log = LoggerFactory.getLogger(TwilioVoiceOtpProvider::class.java)
    }
}

/**
 * The call's TwiML: the code read digit by digit ("4, 7, 1, …" so speech engines don't read it as one number),
 * three times. [code] is digits only, so nothing needs XML escaping.
 */
internal fun voiceTwiml(code: String): String {
    require(code.all { it in '0'..'9' }) { "code must be digits" }
    val spoken = code.toList().joinToString(", ")
    return "<Response><Say loop=\"3\">Your OpenCaller business code is $spoken.</Say></Response>"
}
