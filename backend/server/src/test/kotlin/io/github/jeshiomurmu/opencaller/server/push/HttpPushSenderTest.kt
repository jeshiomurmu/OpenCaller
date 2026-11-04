package io.github.jeshiomurmu.opencaller.server.push

import com.google.crypto.tink.apps.webpush.WebPushHybridDecrypt
import com.sun.net.httpserver.HttpServer
import io.github.jeshiomurmu.opencaller.server.config.FcmConfig
import io.github.jeshiomurmu.opencaller.server.config.ServiceAccountKey
import io.github.jeshiomurmu.opencaller.server.config.VapidConfig
import io.github.jeshiomurmu.opencaller.server.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FCM HTTP v1 and Web Push requests against a local stand-in: request shapes, headers, and what each status means. */
class HttpPushSenderTest {
    private class Received(
        val path: String,
        val headers: Map<String, String?>,
        val body: ByteArray,
    )

    private val received = mutableListOf<Received>()
    private val statuses = ArrayDeque<Int>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes()
                val headers =
                    listOf(
                        "Authorization",
                        "TTL",
                        "Urgency",
                        "Content-Encoding",
                    ).associateWith { exchange.requestHeaders.getFirst(it) }
                received += Received(exchange.requestURI.rawPath, headers, body)
                val (status, text) =
                    if (exchange.requestURI.path == "/token") {
                        200 to """{"access_token":"fcm-access","expires_in":3599}"""
                    } else {
                        (statuses.removeFirstOrNull() ?: 201) to "{}"
                    }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val random = SecureRandom()
    private val clock = MutableClock()
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val fcm =
        FcmConfig(
            "opencaller-test",
            ServiceAccountKey("push@test.iam.gserviceaccount.com", "k1", rsa.private as RSAPrivateKey, "$base/token"),
            base,
        )
    private val vapidPair = KeyPairGenerator.getInstance("EC").apply { initialize(VapidConfig.P256, random) }.generateKeyPair()
    private val vapid = VapidConfig(vapidPair.public as ECPublicKey, vapidPair.private as ECPrivateKey, "mailto:ops@example.org")
    private val sender = HttpPushSender(fcm, vapid, clock, random)
    private val item = UUID.fromString("7b0a0c7e-1f7c-4d73-9d0e-2cbe0e6f6a10")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun fcmGetsTheItemIdAsDataWithHighPriority() =
        runBlocking<Unit> {
            assertEquals(PushOutcome.DELIVERED, sender.send(PushTarget.Fcm("device-token"), item, 45, urgent = true))
            val send = received.last()
            assertEquals("/v1/projects/opencaller-test/messages:send", send.path)
            assertEquals("Bearer fcm-access", send.headers["Authorization"])
            val message =
                Json
                    .parseToJsonElement(send.body.decodeToString())
                    .jsonObject
                    .getValue("message")
                    .jsonObject
            assertEquals("device-token", message.getValue("token").jsonPrimitive.content)
            // The only data is the item id (ADR 0007): no names, numbers or kinds.
            assertEquals(mapOf("id" to item.toString()), message.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content })
            val android = message.getValue("android").jsonObject
            assertEquals("HIGH", android.getValue("priority").jsonPrimitive.content)
            assertEquals("45s", android.getValue("ttl").jsonPrimitive.content)
            assertTrue("notification" !in message)
        }

    @Test
    fun webPushSendsAnEncryptedBodyWithVapidAndUrgency() =
        runBlocking<Unit> {
            val subscriber = KeyPairGenerator.getInstance("EC").apply { initialize(VapidConfig.P256, random) }.generateKeyPair()
            val publicKey = VapidConfig.uncompressed(subscriber.public as ECPublicKey)
            val auth = ByteArray(16).also(random::nextBytes)
            val target = PushTarget.WebPush(URI("$base/up/abc"), publicKey, auth)

            assertEquals(PushOutcome.DELIVERED, sender.send(target, item, 60, urgent = false))
            val push = received.last()
            assertEquals("/up/abc", push.path)
            assertEquals("aes128gcm", push.headers["Content-Encoding"])
            assertEquals("60", push.headers["TTL"])
            assertEquals("normal", push.headers["Urgency"])
            assertTrue(push.headers["Authorization"]!!.startsWith("vapid t="))
            assertTrue(push.headers["Authorization"]!!.endsWith(", k=${vapid.publicKeyBase64Url}"))
            val plain =
                WebPushHybridDecrypt
                    .Builder()
                    .withAuthSecret(auth)
                    .withRecipientPublicKey(publicKey)
                    .withRecipientPrivateKey(subscriber.private as ECPrivateKey)
                    .build()
                    .decrypt(push.body, null)
            assertEquals("""{"id":"$item"}""", plain.decodeToString())
        }

    @Test
    fun goneTargetsAreReportedAndOtherErrorsAreFailures() =
        runBlocking<Unit> {
            val target = PushTarget.WebPush(URI("$base/up/x"), VapidConfig.uncompressed(vapid.publicKey), ByteArray(16))
            statuses += 410
            assertEquals(PushOutcome.GONE, sender.send(target, item, 60, urgent = true))
            statuses += 404
            assertEquals(PushOutcome.GONE, sender.send(PushTarget.Fcm("old-token"), item, 60, urgent = true))
            statuses += 503
            assertEquals(PushOutcome.FAILED, sender.send(PushTarget.Fcm("t"), item, 60, urgent = true))
            assertEquals(PushOutcome.UNSUPPORTED, HttpPushSender(null, null, clock).send(PushTarget.Fcm("t"), item, 60, urgent = true))
            assertEquals("AAEC", Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0, 1, 2)))
        }
}
