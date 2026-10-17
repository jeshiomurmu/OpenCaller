package io.github.jeshiomurmu.opencaller.server.storage

import io.github.jeshiomurmu.opencaller.server.config.MinioConfig
import io.github.jeshiomurmu.opencaller.server.testing.MinioContainer
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import kotlinx.coroutines.runBlocking
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
