package io.github.jeshiomurmu.opencaller.server.auth.otp

import com.sun.net.httpserver.HttpServer
import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.config.TwilioConfig
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The request Twilio's CreateMessage operation expects, against a local stand-in server. */
class TwilioOtpProviderTest {
    private data class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val form: Map<String, String>,
    )

    private val received = mutableListOf<Received>()
    private var status = 201
    private var responseBody = """{"sid":"SM123","status":"queued"}"""
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val form =
                    body.split("&").associate { pair ->
                        val (k, v) = pair.split("=", limit = 2)
                        k to URLDecoder.decode(v, Charsets.UTF_8)
                    }
                received +=
                    Received(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Content-Type"),
                        form,
                    )
                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun provider(
        from: String? = "+15005550006",
        service: String? = null,
    ) = TwilioOtpProvider(TwilioConfig("AC0123456789abcdef", "token", from, service, "http://127.0.0.1:${server.address.port}"))

    private val number = E164.parseOrNull("+12015550123")!!

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun sendsTheCodeAsTwilioDocumentsIt() =
        runBlocking {
            provider().send(number, "123456", 5.minutes)
            val request = received.single()
            assertEquals("POST", request.method)
            assertEquals("/2010-04-01/Accounts/AC0123456789abcdef/Messages.json", request.path)
            assertEquals("Basic " + Base64.getEncoder().encodeToString("AC0123456789abcdef:token".toByteArray()), request.authorization)
            assertEquals("application/x-www-form-urlencoded", request.contentType)
            assertEquals("+12015550123", request.form["To"])
            assertEquals("+15005550006", request.form["From"])
            assertEquals("300", request.form["ValidityPeriod"])
            assertTrue(request.form["Body"]!!.contains("123456") && request.form["Body"]!!.length <= 1600)
        }

    @Test
    fun canSendThroughAMessagingService() =
        runBlocking {
            provider(from = null, service = "MG0123").send(number, "123456", 5.minutes)
            val form = received.single().form
            assertEquals("MG0123", form["MessagingServiceSid"])
            assertTrue("From" !in form)
        }

    @Test
    fun anyStatusOtherThan201IsADeliveryFailure() {
        status = 400
        responseBody = """{"code":21211,"message":"Invalid 'To' Phone Number","status":400}"""
        assertFailsWith<OtpDeliveryException> { runBlocking { provider().send(number, "123456", 5.minutes) } }
        status = 500
        responseBody = "not json"
        assertFailsWith<OtpDeliveryException> { runBlocking { provider().send(number, "123456", 5.minutes) } }
    }

    @Test
    fun anUnreachableServerIsADeliveryFailure() {
        server.stop(0)
        assertFailsWith<OtpDeliveryException> { runBlocking { provider().send(number, "123456", 5.minutes) } }
    }
}
