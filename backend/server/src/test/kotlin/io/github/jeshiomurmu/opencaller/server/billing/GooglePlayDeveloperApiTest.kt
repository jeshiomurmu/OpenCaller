package io.github.jeshiomurmu.opencaller.server.billing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import io.github.jeshiomurmu.opencaller.server.config.GooglePlayConfig
import io.github.jeshiomurmu.opencaller.server.config.ServiceAccountKey
import io.github.jeshiomurmu.opencaller.server.testing.MutableClock
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Play Developer API client against a local stand-in for Google's token endpoint and `androidpublisher` v3,
 * with the request and response shapes of the discovery document.
 */
class GooglePlayDeveloperApiTest {
    private data class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val received = mutableListOf<Received>()
    private val responses = ArrayDeque<Pair<Int, String>>()
    private var tokensIssued = 0
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                received +=
                    Received(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("Authorization"), body)
                val (status, text) =
                    if (exchange.requestURI.path == "/token") {
                        tokensIssued++
                        200 to """{"access_token":"access-$tokensIssued","expires_in":3599,"token_type":"Bearer"}"""
                    } else {
                        responses.removeFirstOrNull() ?: (200 to "{}")
                    }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val clock = MutableClock(Instant.parse("2026-11-03T10:00:00Z"))
    private val key =
        ServiceAccountKey("billing@opencaller-test.iam.gserviceaccount.com", "key-1", keys.private as RSAPrivateKey, "$base/token")
    private val api =
        GooglePlayDeveloperApi(GooglePlayConfig("io.github.jeshiomurmu.opencaller", "opencaller_supporter", key, SECRET, base), clock)

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun getsAnAccessTokenWithASignedServiceAccountJwt() =
        runBlocking<Unit> {
            responses += 200 to ACTIVE
            api.subscription("tok.en-1")
            val tokenRequest = received.first()
            assertEquals("POST", tokenRequest.method)
            val form =
                tokenRequest.body.split("&").associate {
                    it.substringBefore("=") to
                        URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
                }
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
            val jwt =
                JWT
                    .require(Algorithm.RSA256(keys.public as RSAPublicKey, null))
                    .withIssuer(key.clientEmail)
                    .withAudience("$base/token")
                    .withClaim("scope", "https://www.googleapis.com/auth/androidpublisher")
                    .acceptLeeway(TEN_YEARS_SECONDS) // the test clock is fixed, not the wall clock
                    .build()
                    .verify(form["assertion"])
            assertEquals("key-1", jwt.keyId)
            assertEquals(3600L, jwt.expiresAtAsInstant.epochSecond - jwt.issuedAtAsInstant.epochSecond)
            assertEquals(clock.now.epochSecond, jwt.issuedAtAsInstant.epochSecond)
        }

    @Test
    fun readsASubscriptionAsTheDiscoveryDocumentDescribesIt() =
        runBlocking<Unit> {
            responses += 200 to ACTIVE
            val subscription = api.subscription("tok.en-1")!!
            val get = received.last()
            assertEquals("GET", get.method)
            assertEquals(
                "/androidpublisher/v3/applications/io.github.jeshiomurmu.opencaller/purchases/subscriptionsv2/tokens/tok.en-1",
                get.path,
            )
            assertEquals("Bearer access-1", get.authorization)
            assertEquals("SUBSCRIPTION_STATE_ACTIVE", subscription.state)
            assertEquals(false, subscription.acknowledged)
            assertEquals(true, subscription.testPurchase)
            assertEquals("acct-123", subscription.obfuscatedAccountId)
            assertEquals("old-token", subscription.linkedPurchaseToken)
            val item = subscription.lineItems.single()
            assertEquals(PlayLineItem("opencaller_supporter", "gold-yearly", Instant.parse("2026-12-03T10:00:00.123Z"), true), item)
        }

    @Test
    fun anUnknownTokenIsNullAndOtherErrorsThrow() =
        runBlocking<Unit> {
            responses += 404 to """{"error":{"code":404}}"""
            assertNull(api.subscription("missing"))
            responses += 410 to "{}"
            assertNull(api.subscription("gone"))
            responses += 500 to "{}"
            val error = assertFailsWith<PlayApiException> { api.subscription("tok") }
            assertEquals(500, error.status)
        }

    @Test
    fun reusesTheAccessTokenAndRenewsItAfterA401OrWhenItExpires() =
        runBlocking<Unit> {
            repeat(2) { responses += 200 to ACTIVE }
            api.subscription("a")
            api.subscription("b")
            assertEquals(1, tokensIssued)
            responses += 401 to "{}"
            responses += 200 to ACTIVE
            api.subscription("c")
            assertEquals(2, tokensIssued)
            assertEquals("Bearer access-2", received.last().authorization)
            clock.advance(kotlin.time.Duration.parse("1h"))
            responses += 200 to ACTIVE
            api.subscription("d")
            assertEquals(3, tokensIssued)
        }

    @Test
    fun acknowledgesThroughTheSubscriptionsResource() =
        runBlocking<Unit> {
            responses += 204 to ""
            api.acknowledge("opencaller_supporter", "tok.en-1")
            val ack = received.last()
            assertEquals("POST", ack.method)
            assertEquals(
                "/androidpublisher/v3/applications/io.github.jeshiomurmu.opencaller/purchases/subscriptions/opencaller_supporter" +
                    "/tokens/tok.en-1:acknowledge",
                ack.path,
            )
            assertEquals("{}", ack.body)
            responses += 400 to "{}"
            assertFailsWith<PlayApiException> { api.acknowledge("opencaller_supporter", "tok") }
        }

    @Test
    fun parsesAServiceAccountKeyFile() {
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val json =
            """{"type":"service_account","project_id":"p","private_key_id":"k1","private_key":${kotlinx.serialization.json.JsonPrimitive(
                pem,
            )},""" +
                """"client_email":"a@p.iam.gserviceaccount.com","client_id":"1","token_uri":"https://oauth2.googleapis.com/token"}"""
        val parsed = ServiceAccountKey.parse(json)
        assertEquals("a@p.iam.gserviceaccount.com", parsed.clientEmail)
        assertEquals("https://oauth2.googleapis.com/token", parsed.tokenUri)
        assertTrue(parsed.privateKey.encoded.contentEquals(keys.private.encoded))
        assertTrue("***" in parsed.toString() && "BEGIN" !in parsed.toString())
        assertFailsWith<IllegalArgumentException> { ServiceAccountKey.parse("""{"type":"authorized_user"}""") }
        assertFailsWith<IllegalArgumentException> { ServiceAccountKey.parse(json.replace("MII", "!!!")) }
        assertFailsWith<IllegalArgumentException> { ServiceAccountKey.parse("not json") }
    }

    private companion object {
        const val SECRET = "test-billing-secret-with-32-chars-or-more"
        const val TEN_YEARS_SECONDS = 10L * 365 * 86_400
        const val ACTIVE = """{
            "kind": "androidpublisher#subscriptionPurchaseV2",
            "regionCode": "BD",
            "startTime": "2026-11-03T09:59:00Z",
            "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
            "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
            "testPurchase": {},
            "linkedPurchaseToken": "old-token",
            "externalAccountIdentifiers": {"obfuscatedExternalAccountId": "acct-123"},
            "lineItems": [{
                "productId": "opencaller_supporter",
                "expiryTime": "2026-12-03T10:00:00.123Z",
                "autoRenewingPlan": {"autoRenewEnabled": true},
                "offerDetails": {"basePlanId": "gold-yearly", "offerTags": []},
                "latestSuccessfulOrderId": "GPA.0000-0000-0000-00000"
            }]
        }"""
    }
}
