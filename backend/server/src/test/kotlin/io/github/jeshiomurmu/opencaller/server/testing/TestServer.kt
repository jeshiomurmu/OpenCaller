package io.github.jeshiomurmu.opencaller.server.testing

import io.github.jeshiomurmu.opencaller.server.ServerDependencies
import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpProvider
import io.github.jeshiomurmu.opencaller.server.config.AppConfig
import io.github.jeshiomurmu.opencaller.server.config.DatabaseConfig
import io.github.jeshiomurmu.opencaller.server.config.Environment
import io.github.jeshiomurmu.opencaller.server.config.HttpConfig
import io.github.jeshiomurmu.opencaller.server.config.JwtConfig
import io.github.jeshiomurmu.opencaller.server.config.MinioConfig
import io.github.jeshiomurmu.opencaller.server.config.OtpConfig
import io.github.jeshiomurmu.opencaller.server.config.OtpProviderKind
import io.github.jeshiomurmu.opencaller.server.config.RedisConfig
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import kotlinx.coroutines.Dispatchers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private const val REDIS_PORT = 6379

/** Redis in a container (the same image as infra/docker/compose.yml). */
class RedisContainer : GenericContainer<RedisContainer>(DockerImageName.parse("redis:8.8")) {
    init {
        withExposedPorts(REDIS_PORT)
    }

    val url: String get() = "redis://$host:${getMappedPort(REDIS_PORT)}"
}

/** One PostgreSQL and one Redis container for the whole test run, started on first use. */
object TestInfra {
    private val postgres: PostgreSQLContainer by lazy { PostgreSQLContainer(DockerImageName.parse("postgres:18")).apply { start() } }
    private val redisContainer: RedisContainer by lazy { RedisContainer().apply { start() } }

    val databaseConfig: DatabaseConfig by lazy { DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxPoolSize = 5) }

    /** Migrated by Flyway once. */
    val database: ServerDatabase by lazy { ServerDatabase.connect(databaseConfig, Dispatchers.IO) }

    val redisUrl: String by lazy { redisContainer.url }
    val redis: Redis by lazy { Redis.connect(redisUrl) }
}

/** A clock tests can move. */
class MutableClock(
    var now: Instant = Instant.parse("2026-10-16T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by.toJavaDuration())
    }
}

/** Records codes instead of sending them; [failNext] makes the next send fail. */
class RecordingOtpProvider : OtpProvider {
    val sent = mutableListOf<Pair<E164, String>>()
    var failNext = false

    override suspend fun send(
        number: E164,
        code: String,
        validFor: Duration,
    ) {
        if (failNext) {
            failNext = false
            throw OtpDeliveryException("test failure")
        }
        sent += number to code
    }

    fun lastCodeFor(number: String): String = sent.last { it.first.value == number }.second
}

/** A valid test configuration pointing at [TestInfra]. */
fun testConfig(): AppConfig =
    AppConfig(
        environment = Environment.TEST,
        version = "test",
        http = HttpConfig(port = 8080, metricsPort = 9090),
        database = TestInfra.databaseConfig,
        redis = RedisConfig(TestInfra.redisUrl),
        minio = MinioConfig("http://localhost:9000", "test", "test-secret"),
        jwt = JwtConfig("test-jwt-secret-with-at-least-32-chars!!", "opencaller", "opencaller-app"),
        otp = OtpConfig("test-otp-pepper-with-at-least-32-chars!!", OtpProviderKind.LOG),
        twilio = null,
    )

/** Server dependencies on the shared containers with a test clock and a recording OTP provider; state is wiped. */
class TestServer {
    val clock = MutableClock()
    val otp = RecordingOtpProvider()
    val deps = ServerDependencies(testConfig(), TestInfra.database, TestInfra.redis, otp, clock)

    /** Empties Redis and the auth tables so tests don't see each other's codes, limits or users. */
    suspend fun reset() {
        TestInfra.redis.evalLong("redis.call('FLUSHALL') return 1", emptyList())
        TestInfra.database.query { exec("TRUNCATE users, devices, refresh_tokens CASCADE") }
    }
}
