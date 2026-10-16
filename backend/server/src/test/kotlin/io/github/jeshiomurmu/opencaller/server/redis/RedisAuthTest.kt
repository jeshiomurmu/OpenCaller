package io.github.jeshiomurmu.opencaller.server.redis

import io.github.jeshiomurmu.opencaller.server.testing.RedisContainer
import io.lettuce.core.RedisConnectionException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The server authenticates with REDIS_PASSWORD (kept out of the URL), as against the compose Redis. */
class RedisAuthTest {
    private val password = "pa:ss@word/with#specials"
    private val container = RedisContainer().withCommand("redis-server", "--requirepass", password).apply { start() }

    @AfterTest
    fun stop() = container.stop()

    @Test
    fun connectsWithThePassword() =
        runBlocking {
            Redis.connect(container.url, password).use { assertEquals("PONG", it.ping()) }
        }

    @Test
    fun refusesToConnectWithoutIt() {
        assertFailsWith<RedisConnectionException> { Redis.connect(container.url).close() }
    }
}
