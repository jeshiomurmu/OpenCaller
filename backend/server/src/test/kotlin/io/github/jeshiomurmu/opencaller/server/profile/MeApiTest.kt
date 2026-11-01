package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.lookup.BucketResponse
import io.github.jeshiomurmu.opencaller.server.lookup.NumberKey
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET/PUT /v1/me` and `POST /v1/me/avatar` (docs/prd.md F-SO-01/04, TC-SOC-001). */
class MeApiTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val number = "+8801712345678"

    private suspend fun HttpResponse.me(): MeResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        return testJson.decodeFromString(MeResponse.serializer(), bodyAsText())
    }

    private suspend fun HttpClient.put(
        token: String,
        name: String?,
        visibility: String = "registered_only",
        incognito: Boolean = false,
        avatarKey: String? = null,
    ): HttpResponse {
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val avatarJson = avatarKey?.let { "\"$it\"" } ?: "null"
        return putJson(
            "/v1/me",
            """{"displayName":$nameJson,"visibility":"$visibility","incognito":$incognito,"avatarKey":$avatarJson}""",
            token,
        )
    }

    private suspend fun HttpClient.nameInBucket(token: String?): String? {
        val key = NumberKey.of(number)
        val response = getWith("/v1/lookup/buckets/${key.countryCode}/${key.bucket16}", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson
            .decodeFromString(BucketResponse.serializer(), response.bodyAsText())
            .entries
            .firstNotNullOfOrNull { it.info.name }
    }

    @Test
    fun aNewAccountStartsWithTheDefaults() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            val me = client.getWith("/v1/me", token).me()
            assertEquals(number, me.e164)
            assertNull(me.displayName)
            assertEquals("registered_only", me.visibility)
            assertFalse(me.incognito)
            assertNull(me.badge)
            assertFalse(me.supporter)
            assertEquals(0, me.viewerCount + me.searcherCount + me.pendingContactRequests)
        }

    @Test
    fun theProfileIsReplacedAndValidated() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            val me = client.put(token, " Asha Rahman ", "everyone", incognito = true).me()
            assertEquals("Asha Rahman", me.displayName)
            assertEquals("everyone", me.visibility)
            assertTrue(me.incognito)
            assertNull(client.put(token, null).me().displayName)
            client.put(token, "x", "friends").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.put(token, "x".repeat(61)).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.put(token, "two\\nlines").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.put(token, "").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.getWith("/v1/me").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            setSupporterTier(number, "gold")
            val gold = client.getWith("/v1/me", token).me()
            assertEquals("gold", gold.badge)
            assertTrue(gold.supporter)
        }

    // TC-SOC-001: a visibility change shows in bucket lookups at once (buckets are built per request).
    @Test
    fun visibilityDecidesWhoSeesTheNameInBuckets() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            val other = signIn(server, "+12015550123").accessToken
            client.put(token, "Asha Rahman", "everyone").me()
            assertEquals("Asha Rahman", client.nameInBucket(null))
            client.put(token, "Asha Rahman", "registered_only").me()
            assertNull(client.nameInBucket(null))
            assertEquals("Asha Rahman", client.nameInBucket(other))
            client.put(token, "Asha Rahman", "nobody").me()
            assertNull(client.nameInBucket(null))
            assertNull(client.nameInBucket(other))
        }

    @Test
    fun aPictureIsUploadedThroughAPresignedUrlAndChecked() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            val upload = client.avatarUpload(token)
            assertEquals("image/jpeg", upload.contentType)
            assertTrue(upload.uploadUrl.contains(upload.avatarKey))
            // Not uploaded yet.
            client.put(token, "Asha", avatarKey = upload.avatarKey).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            store.upload(upload.avatarKey, ByteArray(2_000) { 1 }, "image/jpeg")
            val me = client.put(token, "Asha", avatarKey = upload.avatarKey).me()
            assertEquals(upload.avatarKey, me.avatarKey)
            assertNotNull(me.avatarUrl)
            // A new picture replaces the old one, which is deleted.
            val second = client.avatarUpload(token)
            store.upload(second.avatarKey, ByteArray(10) { 2 }, "image/jpeg")
            client.put(token, "Asha", avatarKey = second.avatarKey).me()
            assertFalse(store.objects.containsKey(upload.avatarKey))
            // Removing the picture deletes it too.
            assertNull(client.put(token, "Asha").me().avatarUrl)
            assertFalse(store.objects.containsKey(second.avatarKey))
        }

    @Test
    fun picturesThatAreNotJpegsOrNotOwnAreRefused() =
        apiTest(server) {
            val token = signIn(server, number).accessToken
            val upload = client.avatarUpload(token)
            store.upload(upload.avatarKey, ByteArray(10), "image/png")
            client.put(token, "Asha", avatarKey = upload.avatarKey).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            assertFalse(store.objects.containsKey(upload.avatarKey), "a refused upload is deleted")
            val big = client.avatarUpload(token)
            store.upload(big.avatarKey, ByteArray(1_000_001), "image/jpeg")
            client.put(token, "Asha", avatarKey = big.avatarKey).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            // Another user's key, or a made-up one, can't be set.
            val otherToken = signIn(server, "+12015550123").accessToken
            val theirs = client.avatarUpload(otherToken)
            store.upload(theirs.avatarKey, ByteArray(10), "image/jpeg")
            client.put(token, "Asha", avatarKey = theirs.avatarKey).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.put(token, "Asha", avatarKey = "lists/BD/1/full.ocsl").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
        }

    private suspend fun HttpClient.avatarUpload(token: String): AvatarUploadResponse {
        val response = postJson("/v1/me/avatar", "{}", mapOf(HttpHeaders.Authorization to "Bearer $token"))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(AvatarUploadResponse.serializer(), response.bodyAsText())
    }
}
