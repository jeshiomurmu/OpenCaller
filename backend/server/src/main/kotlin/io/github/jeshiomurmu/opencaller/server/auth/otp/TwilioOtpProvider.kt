package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.config.TwilioConfig
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Sends codes with Twilio Programmable Messaging, written against Twilio's official API definition
 * (twilio-oai `twilio_api_v2010.json`, operation `CreateMessage`):
 * `POST {baseUrl}/2010-04-01/Accounts/{AccountSid}/Messages.json`, HTTP Basic auth (Account SID : Auth Token),
 * form-encoded `To` (E.164), `From` or `MessagingServiceSid`, `Body` (≤ 1,600 chars) and `ValidityPeriod`
 * (1–36,000 s, so an SMS stuck in Twilio's queue is dropped once the code has expired). Success is `201 Created`.
 */
class TwilioOtpProvider(
    private val config: TwilioConfig,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) : OtpProvider {
    private val endpoint = URI.create("${config.baseUrl.trimEnd('/')}/2010-04-01/Accounts/${config.accountSid}/Messages.json")
    private val authorization =
        "Basic " + Base64.getEncoder().encodeToString("${config.accountSid}:${config.authToken}".toByteArray(Charsets.UTF_8))

    override suspend fun send(
        number: E164,
        code: String,
        validFor: Duration,
    ) {
        val sender = config.from?.let { "From" to it } ?: ("MessagingServiceSid" to requireNotNull(config.messagingServiceSid))
        val form =
            listOf(
                "To" to number.value,
                sender,
                "Body" to otpMessage(code, validFor),
                "ValidityPeriod" to validFor.inWholeSeconds.toString(),
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
            // The API definition doesn't describe error bodies, so only log `code` / `message` if a JSON body has
            // them (numbers in the text are redacted by the log encoder). Any non-201 status means "not sent".
            val error = parseError(response.body())
            log.warn("Twilio refused the message: HTTP {} code {} {}", response.statusCode(), error["code"], error["message"])
            throw OtpDeliveryException("Twilio returned HTTP ${response.statusCode()}")
        }
    }

    private fun parseError(body: String): Map<String, String?> =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
            .let { obj -> listOf("code", "message").associateWith { key -> obj[key]?.jsonPrimitive?.content } }

    private companion object {
        const val CREATED = 201
        val TIMEOUT = 10.seconds
        val json = Json { ignoreUnknownKeys = true }
        val log = LoggerFactory.getLogger(TwilioOtpProvider::class.java)
    }
}
