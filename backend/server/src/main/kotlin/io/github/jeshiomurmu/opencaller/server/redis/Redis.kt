package io.github.jeshiomurmu.opencaller.server.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import java.io.Closeable

/**
 * One thread-safe Lettuce connection. Everything the server keeps in Redis (rate-limit windows, one-time codes)
 * is changed by Lua scripts, so each check-and-update is atomic.
 */
class Redis private constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String>,
) : Closeable {
    /** Runs a Lua [script] that returns an array of integers. (Lettuce's eval takes the arguments as varargs only.) */
    @Suppress("SpreadOperator")
    suspend fun evalLongs(
        script: String,
        keys: List<String>,
        args: List<String> = emptyList(),
    ): List<Long> {
        val result: List<Any?> =
            connection
                .async()
                .eval<List<Any?>>(script, ScriptOutputType.MULTI, keys.toTypedArray(), *args.toTypedArray())
                .await()
        return result.map { (it as Number).toLong() }
    }

    /** Runs a Lua [script] that returns one integer. */
    @Suppress("SpreadOperator")
    suspend fun evalLong(
        script: String,
        keys: List<String>,
        args: List<String> = emptyList(),
    ): Long =
        connection
            .async()
            .eval<Long>(script, ScriptOutputType.INTEGER, keys.toTypedArray(), *args.toTypedArray())
            .await()

    /** `HGET` [key] [field]. */
    suspend fun hget(
        key: String,
        field: String,
    ): String? = connection.async().hget(key, field).await()

    /** Seconds until [key] expires (`TTL`: -1 no expiry, -2 no such key). */
    suspend fun ttl(key: String): Long = connection.async().ttl(key).await()

    /** Deletes [keys]. */
    suspend fun delete(vararg keys: String) {
        connection.async().del(*keys).await()
    }

    /** `PING`, for readiness checks. */
    suspend fun ping(): String = connection.async().ping().await()

    override fun close() {
        connection.close()
        client.shutdown()
    }

    /** Connects to [url] (`redis://host:port[/db]`), authenticating with [password] if given. */
    companion object {
        fun connect(
            url: String,
            password: String? = null,
        ): Redis {
            val uri = RedisURI.create(url).apply { password?.let { setAuthentication(it) } }
            val client = RedisClient.create(uri)
            return Redis(client, client.connect())
        }
    }
}
