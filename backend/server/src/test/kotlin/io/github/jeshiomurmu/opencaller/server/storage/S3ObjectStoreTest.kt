package io.github.jeshiomurmu.opencaller.server.storage

import io.github.jeshiomurmu.opencaller.server.config.MinioConfig
import io.github.jeshiomurmu.opencaller.server.testing.MinioContainer
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The S3 client against a real MinIO (which checks every signature). */
class S3ObjectStoreTest {
    private fun store(
        secret: String = MinioContainer.SECRET_KEY,
        bucket: String = "store-test",
    ) = S3ObjectStore(
        MinioConfig(TestInfra.minio.endpoint, MinioContainer.ACCESS_KEY, secret, bucket = bucket),
        Clock.systemUTC(),
    )

    @Test
    fun putGetOverwriteAndMissing() =
        runBlocking {
            val store = store()
            val bytes = ByteArray(70_000) { (it % 251).toByte() }
            store.put("lists/BD/1/full.ocsl", bytes, "application/octet-stream")
            assertContentEquals(bytes, store.get("lists/BD/1/full.ocsl"))
            store.put("lists/BD/1/full.ocsl", byteArrayOf(1, 2, 3), "application/octet-stream")
            assertContentEquals(byteArrayOf(1, 2, 3), store.get("lists/BD/1/full.ocsl"))
            // A key that needs encoding.
            store.put("odd key/a+b.txt", "x".toByteArray(), "text/plain")
            assertContentEquals("x".toByteArray(), store.get("odd key/a+b.txt"))
            assertNull(store.get("lists/BD/404/full.ocsl"))
            // A second client finds the bucket already there (409 from MinIO) and still works.
            store().put("again", byteArrayOf(9), "application/octet-stream")
            assertContentEquals(byteArrayOf(9), store().get("again"))
        }

    // What an app does with the URLs from POST /v1/me/avatar and GET /v1/me: MinIO accepts our query signatures.
    @Test
    fun presignedUrlsWorkWithoutCredentialsAndHeadAndDeleteToo() =
        runBlocking {
            val store = store(bucket = "presign-test")
            store.put("warm-up", byteArrayOf(0), "application/octet-stream") // creates the bucket
            val http = HttpClient.newHttpClient()
            val picture = ByteArray(5_000) { (it % 7).toByte() }
            val put =
                HttpRequest
                    .newBuilder(store.presignPut("avatars/u1/p.jpg", "image/jpeg", 600))
                    .header("Content-Type", "image/jpeg")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(picture))
                    .build()
            assertEquals(200, http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(ObjectInfo(5_000, "image/jpeg"), store.head("avatars/u1/p.jpg"))
            val get = HttpRequest.newBuilder(store.presignGet("avatars/u1/p.jpg", 900)).GET().build()
            assertContentEquals(picture, http.send(get, HttpResponse.BodyHandlers.ofByteArray()).body())
            // The signed content type can't be swapped.
            val png =
                HttpRequest
                    .newBuilder(store.presignPut("avatars/u1/q.jpg", "image/jpeg", 600))
                    .header("Content-Type", "image/png")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(picture))
                    .build()
            assertEquals(403, http.send(png, HttpResponse.BodyHandlers.discarding()).statusCode())
            store.delete("avatars/u1/p.jpg")
            assertNull(store.head("avatars/u1/p.jpg"))
            store.delete("avatars/u1/p.jpg") // deleting a missing object is fine
        }

    @Test
    fun aWrongSecretIsAnError() =
        runBlocking {
            assertFailsWith<ObjectStoreException> { store(secret = "wrong-secret-value").put("k", byteArrayOf(1), "text/plain") }
            Unit
        }

    @Test
    fun anUnreachableStoreIsAnError() =
        runBlocking {
            val store = S3ObjectStore(MinioConfig("http://127.0.0.1:1", "a", "b"), Clock.systemUTC())
            assertFailsWith<ObjectStoreException> { store.get("k") }
            Unit
        }
}
