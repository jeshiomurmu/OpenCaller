package io.github.jeshiomurmu.opencaller.server.storage

import io.github.jeshiomurmu.opencaller.server.config.MinioConfig
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The object store failed or could not be reached. */
class ObjectStoreException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Files the server publishes (spam lists) and files apps upload (profile pictures). */
interface ObjectStore {
    /** Stores [bytes] under [key], replacing any object with that key. */
    suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    )

    /** The object under [key], or `null` if there is none. */
    suspend fun get(key: String): ByteArray?

    /** Size and content type of the object under [key], or `null` if there is none. */
    suspend fun head(key: String): ObjectInfo?

    /** Deletes the object under [key] (nothing happens if there is none). */
    suspend fun delete(key: String)

    /**
     * A URL an app can `PUT` one object to, under [key], until [expiresSeconds] from now, without credentials. The
     * upload must carry `Content-Type: [contentType]` (it is signed).
     */
    fun presignPut(
        key: String,
        contentType: String,
        expiresSeconds: Int,
    ): URI

    /** A URL an app can `GET` the object under [key] from, until [expiresSeconds] from now. */
    fun presignGet(
        key: String,
        expiresSeconds: Int,
    ): URI
}

/** What [ObjectStore.head] tells about an object. */
data class ObjectInfo(
    val size: Long,
    val contentType: String?,
)

/**
 * MinIO (or any S3-compatible store) over its REST API with path-style URLs (`{endpoint}/{bucket}/{key}`) and
 * Signature V4 ([SigV4Signer]); no SDK. The bucket is created on first use. Presigned URLs point at
 * [MinioConfig.publicEndpoint], the address apps reach the store at (the server itself uses [MinioConfig.endpoint]).
 */
class S3ObjectStore(
    private val config: MinioConfig,
    private val clock: Clock,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) : ObjectStore {
    private val signer = SigV4Signer(config.accessKey, config.secretKey, config.region)
    private val bucketReady = AtomicBoolean(false)

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        ensureBucket()
        val response = send("PUT", objectUri(key), bytes, contentType)
        if (response.statusCode() != OK) throw failure("PUT", key, response)
    }

    override suspend fun get(key: String): ByteArray? {
        val response = send("GET", objectUri(key), ByteArray(0), null)
        return when (response.statusCode()) {
            OK -> response.body()
            NOT_FOUND -> null
            else -> throw failure("GET", key, response)
        }
    }

    override suspend fun head(key: String): ObjectInfo? {
        val response = send("HEAD", objectUri(key), ByteArray(0), null)
        return when (response.statusCode()) {
            OK -> {
                ObjectInfo(
                    size = response.headers().firstValueAsLong("Content-Length").orElse(0),
                    contentType = response.headers().firstValue("Content-Type").orElse(null),
                )
            }

            NOT_FOUND -> {
                null
            }

            else -> {
                throw failure("HEAD", key, response)
            }
        }
    }

    override suspend fun delete(key: String) {
        val response = send("DELETE", objectUri(key), ByteArray(0), null)
        // S3 answers 204 for a deleted object and for one that never existed.
        if (response.statusCode() != NO_CONTENT && response.statusCode() != OK) throw failure("DELETE", key, response)
    }

    override fun presignPut(
        key: String,
        contentType: String,
        expiresSeconds: Int,
    ): URI =
        signer.presign(
            "PUT",
            objectUri(key, config.publicEndpoint),
            clock.instant(),
            expiresSeconds,
            mapOf("Content-Type" to contentType),
        )

    override fun presignGet(
        key: String,
        expiresSeconds: Int,
    ): URI = signer.presign("GET", objectUri(key, config.publicEndpoint), clock.instant(), expiresSeconds)

    /** Creates the bucket unless it exists (MinIO answers 409 BucketAlreadyOwnedByYou for an existing one). */
    private suspend fun ensureBucket() {
        if (bucketReady.get()) return
        val response = send("PUT", URI.create("$base/${config.bucket}"), ByteArray(0), null)
        if (response.statusCode() != OK && response.statusCode() != CONFLICT) throw failure("PUT", "(bucket)", response)
        bucketReady.set(true)
    }

    private val base = config.endpoint.trimEnd('/')

    private fun objectUri(
        key: String,
        endpoint: String = base,
    ) = URI.create("${endpoint.trimEnd('/')}/${config.bucket}/${SigV4Signer.uriEncode(key, keepSlash = true)}")

    private suspend fun send(
        method: String,
        uri: URI,
        body: ByteArray,
        contentType: String?,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT.toJavaDuration())
        signer.sign(method, uri, body, clock.instant()).forEach { (name, value) -> builder.header(name, value) }
        contentType?.let { builder.header("Content-Type", it) }
        builder.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        return try {
            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        } catch (e: IOException) {
            throw ObjectStoreException("Object store unreachable: $method ${uri.path}", e)
        }
    }

    private fun failure(
        method: String,
        key: String,
        response: HttpResponse<ByteArray>,
    ): ObjectStoreException {
        // S3 error bodies are small XML documents (<Code>…</Code>); log the code, not the whole body.
        val code = Regex("<Code>([^<]{1,64})</Code>").find(response.body()?.decodeToString().orEmpty())?.groupValues?.get(1)
        log.warn("Object store refused {} {}: HTTP {} {}", method, key, response.statusCode(), code)
        return ObjectStoreException("Object store returned HTTP ${response.statusCode()} ($code) for $method $key")
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        val TIMEOUT = 30.seconds
        val log = LoggerFactory.getLogger(S3ObjectStore::class.java)
    }
}
