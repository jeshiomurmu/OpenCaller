package io.github.jeshiomurmu.opencaller.server.billing

import io.github.jeshiomurmu.opencaller.server.config.BillingConfig
import io.github.jeshiomurmu.opencaller.server.config.GooglePlayConfig
import io.github.jeshiomurmu.opencaller.server.config.ServiceAccountKey
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Fakes Google: purchases by token, and the acknowledgements received. */
class FakePlaySubscriptions : PlaySubscriptions {
    val purchases = mutableMapOf<String, PlaySubscription>()
    val acknowledged = mutableListOf<Pair<String, String>>()
    var failing = false

    override suspend fun subscription(purchaseToken: String): PlaySubscription? {
        if (failing) throw PlayApiException("down", 503)
        return purchases[purchaseToken]
    }

    override suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    ) {
        acknowledged += productId to purchaseToken
        purchases[purchaseToken]?.let { purchases[purchaseToken] = it.copy(acknowledged = true) }
    }
}

/** `/v1/server`, `/v1/entitlements` and `/v1/billing/verify`, with Google faked (TC-BILL-001 on the server side). */
class BillingApiTest {
    private val play = FakePlaySubscriptions()

    private fun billingServer(selfHosted: Boolean = false) =
        TestServer(
            config = testConfig().copy(billing = BillingConfig(selfHosted = selfHosted, googlePlay = playConfig)),
            playApi = play,
        )

    private suspend fun userId(number: String): UUID =
        TestInfra.database.query { Users.select(Users.id).where { Users.e164 eq number }.single()[Users.id] }

    private suspend fun tier(number: String): String =
        TestInfra.database.query { Users.select(Users.supporterTier).where { Users.e164 eq number }.single()[Users.supporterTier] }

    @Suppress("LongParameterList") // a test builder whose defaults keep the tests short
    private fun purchase(
        accountId: String?,
        state: String = "SUBSCRIPTION_STATE_ACTIVE",
        basePlan: String = "supporter",
        expiry: Instant,
        product: String = PRODUCT,
        linked: String? = null,
    ) = PlaySubscription(
        state,
        acknowledged = false,
        testPurchase = true,
        linkedPurchaseToken = linked,
        obfuscatedAccountId = accountId,
        lineItems = listOf(PlayLineItem(product, basePlan, expiry, autoRenewing = state == "SUBSCRIPTION_STATE_ACTIVE")),
    )

    private suspend fun ApplicationTestBuilder.verify(
        token: String,
        accessToken: String,
        product: String = PRODUCT,
    ): HttpResponse = client.postJson("/v1/billing/verify", """{"productId":"$product","purchaseToken":"$token"}""", bearer(accessToken))

    private suspend fun ApplicationTestBuilder.entitlements(accessToken: String): EntitlementResponse {
        val response = client.getWith("/v1/entitlements", accessToken)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        return testJson.decodeFromString(EntitlementResponse.serializer(), response.bodyAsText())
    }

    @Test
    fun aServerWithoutBillingSaysSo() =
        apiTest(TestServer()) { server ->
            val info = testJson.decodeFromString(ServerInfoResponse.serializer(), client.getWith("/v1/server").bodyAsText())
            assertEquals(ServerInfoResponse(selfHosted = false, billing = BillingInfo()), info)
            val token = signIn(server, NUMBER).accessToken
            assertEquals(EntitlementResponse(entitled = false, tier = "none", source = "none"), entitlements(token))
            verify("tok", token).assertProblem(HttpStatusCode.ServiceUnavailable, "billing_not_configured")
            client.getWith("/v1/entitlements").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    @Test
    fun aVerifiedPurchaseUnlocksSupporterFeaturesAndIsAcknowledged() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            client.getWith("/v1/me/viewers", token).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            val accountId = entitlements(token).billingAccountId!!
            assertEquals(64, accountId.length)
            assertEquals(server.deps.billing.accountId(userId(NUMBER)), accountId)
            play.purchases["tok-1"] = purchase(accountId, basePlan = "gold-monthly", expiry = server.clock.now.plusSeconds(30L * 86_400))

            val response = verify("tok-1", token)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val result = testJson.decodeFromString(EntitlementResponse.serializer(), response.bodyAsText())
            assertEquals(true, result.entitled)
            assertEquals("gold", result.tier)
            assertEquals("billing", result.source)
            assertEquals("2026-11-15T12:00:00Z", result.expiresAt)
            assertEquals(true, result.autoRenewing)
            assertEquals("gold", tier(NUMBER))
            assertEquals(listOf(PRODUCT to "tok-1"), play.acknowledged)
            assertEquals(HttpStatusCode.OK, client.getWith("/v1/me/viewers", token).status)

            // Sending the same purchase again changes nothing and doesn't acknowledge twice.
            assertEquals(HttpStatusCode.OK, verify("tok-1", token).status)
            assertEquals(1, play.acknowledged.size)
        }

    @Test
    fun aPurchaseOfAnotherAccountOrProductIsRefused() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            val other = signIn(server, OTHER).accessToken
            val otherAccount = entitlements(other).billingAccountId!!
            val expiry = server.clock.now.plusSeconds(86_400)
            play.purchases["theirs"] = purchase(otherAccount, expiry = expiry)
            play.purchases["unbound"] = purchase(null, expiry = expiry)
            verify("theirs", token).assertProblem(HttpStatusCode.Conflict, "purchase_other_account")
            verify("unbound", token).assertProblem(HttpStatusCode.Conflict, "purchase_other_account")

            val mine = entitlements(token).billingAccountId
            play.purchases["plan"] = purchase(mine, basePlan = "platinum", expiry = expiry)
            play.purchases["product"] = purchase(mine, product = "something_else", expiry = expiry)
            verify("plan", token).assertProblem(HttpStatusCode.BadRequest, "unknown_product")
            verify("product", token).assertProblem(HttpStatusCode.BadRequest, "unknown_product")
            verify("tok", token, product = "something_else").assertProblem(HttpStatusCode.BadRequest, "unknown_product")
            verify("unknown", token).assertProblem(HttpStatusCode.BadRequest, "invalid_purchase")
            verify("not a token!", token).assertProblem(HttpStatusCode.BadRequest, "invalid_purchase")
            play.failing = true
            verify("unknown", token).assertProblem(HttpStatusCode.BadGateway, "billing_unavailable")
            assertEquals("none", tier(NUMBER))
            assertTrue(play.acknowledged.isEmpty())
        }

    @Test
    fun aCanceledSubscriptionLastsUntilItsPeriodEndsAndTheHourlyCheckEndsIt() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            val account = entitlements(token).billingAccountId
            play.purchases["tok-1"] =
                purchase(account, state = "SUBSCRIPTION_STATE_CANCELED", expiry = server.clock.now.plusSeconds(86_400))
            assertEquals(HttpStatusCode.OK, verify("tok-1", token).status)
            assertEquals("supporter", tier(NUMBER))
            assertTrue(play.acknowledged.isEmpty()) // only paid states are acknowledged

            server.clock.advance(2.days)
            play.purchases["tok-1"] = play.purchases.getValue("tok-1").copy(state = "SUBSCRIPTION_STATE_EXPIRED")
            val run = server.deps.billing.recheck()
            assertEquals(1, run.checked)
            assertEquals("none", tier(NUMBER))
            val fresh = signIn(server, NUMBER).accessToken // the first access token has expired by now
            val after = entitlements(fresh)
            assertEquals(false, after.entitled)
            assertNull(after.expiresAt)
            client.getWith("/v1/me/viewers", fresh).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
        }

    @Test
    fun theHourlyCheckPicksUpARenewal() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            val account = entitlements(token).billingAccountId
            val end = server.clock.now.plusSeconds(3600)
            play.purchases["tok-1"] = purchase(account, expiry = end)
            verify("tok-1", token)
            // Google renews the subscription for a month; the check an hour later sees it before access ends.
            play.purchases["tok-1"] =
                play.purchases
                    .getValue(
                        "tok-1",
                    ).copy(lineItems = listOf(PlayLineItem(PRODUCT, "supporter", end.plusSeconds(30L * 86_400), true)))
            server.clock.advance(2.hours)
            assertEquals(
                1,
                server.deps.billing
                    .recheck()
                    .checked,
            )
            server.clock.advance(3.days)
            server.deps.billing.recheck()
            assertEquals("supporter", tier(NUMBER))
            assertEquals(true, entitlements(signIn(server, NUMBER).accessToken).entitled)
        }

    @Test
    fun anUpgradeReplacesThePurchaseAndAStaleOneDoesNot() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            val account = entitlements(token).billingAccountId
            val month = server.clock.now.plusSeconds(30L * 86_400)
            play.purchases["old"] = purchase(account, expiry = month)
            verify("old", token)
            play.purchases["new"] = purchase(account, basePlan = "family", expiry = month, linked = "old")
            verify("new", token)
            assertEquals("family", tier(NUMBER))
            assertEquals("family", entitlements(token).tier)

            play.purchases["expired"] =
                purchase(account, state = "SUBSCRIPTION_STATE_EXPIRED", basePlan = "gold", expiry = server.clock.now.minusSeconds(60))
            assertEquals(HttpStatusCode.OK, verify("expired", token).status)
            assertEquals("family", tier(NUMBER))
        }

    @Test
    fun aSelfHostedServerUnlocksEveryoneWithoutABadge() =
        apiTest(billingServer(selfHosted = true)) { server ->
            val info = testJson.decodeFromString(ServerInfoResponse.serializer(), client.getWith("/v1/server").bodyAsText())
            assertEquals(true, info.selfHosted)
            assertEquals(PRODUCT, info.billing.googlePlayProductId)
            val token = signIn(server, NUMBER).accessToken
            val result = entitlements(token)
            assertEquals(true, result.entitled)
            assertEquals("none", result.tier)
            assertEquals("self_hosted", result.source)
            assertEquals(HttpStatusCode.OK, client.getWith("/v1/me/viewers", token).status)
        }

    @Test
    fun aTierGrantedByHandStaysThroughTheHourlyCheck() =
        apiTest(billingServer()) { server ->
            val token = signIn(server, NUMBER).accessToken
            setSupporterTier(NUMBER, "gold")
            server.deps.billing.recheck()
            assertEquals("gold", tier(NUMBER))
            assertEquals("granted", entitlements(token).source)
        }

    private companion object {
        const val PRODUCT = "opencaller_supporter"
        const val NUMBER = "+14155550101"
        const val OTHER = "+14155550102"
        val playConfig =
            GooglePlayConfig(
                packageName = "io.github.jeshiomurmu.opencaller",
                productId = PRODUCT,
                serviceAccount =
                    ServiceAccountKey(
                        "billing@test.iam.gserviceaccount.com",
                        "k1",
                        KeyPairGenerator
                            .getInstance("RSA")
                            .apply { initialize(2048) }
                            .generateKeyPair()
                            .private as RSAPrivateKey,
                        "http://127.0.0.1:1/token",
                    ),
                accountSecret = "test-billing-secret-with-32-chars-or-more",
            )
    }
}
