package io.github.jeshiomurmu.opencaller.server.testing

import io.github.jeshiomurmu.opencaller.server.ServerDependencies
import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpProvider
import io.github.jeshiomurmu.opencaller.server.config.AdminConfig
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
import io.github.jeshiomurmu.opencaller.server.db.allTables
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import kotlinx.coroutines.Dispatchers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
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

private const val MINIO_PORT = 9000

/**
 * MinIO in a container for the object-store tests. CI uses the same Chainguard image as compose; set
 * `MINIO_TEST_IMAGE` to use another MinIO image (e.g. where cgr.dev is unreachable).
 */
class MinioContainer :
    GenericContainer<MinioContainer>(
        DockerImageName.parse(System.getenv("MINIO_TEST_IMAGE") ?: "cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio"),
    ) {
    init {
        withExposedPorts(MINIO_PORT)
        withEnv("MINIO_ROOT_USER", ACCESS_KEY)
        withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
        // A tmpfs every user can write to: the image runs as a non-root user.
        withTmpFs(mapOf("/data" to "rw,mode=1777"))
        withCommand("server", "/data")
        waitingFor(Wait.forHttp("/minio/health/live").forPort(MINIO_PORT))
    }

    val endpoint: String get() = "http://$host:${getMappedPort(MINIO_PORT)}"

    /** Test credentials (not secrets). */
    companion object {
        const val ACCESS_KEY = "opencaller-test"
        const val SECRET_KEY = "opencaller-test-secret"
    }
}

/** In-memory [ObjectStore] for API tests that don't need real MinIO. */
class InMemoryObjectStore : ObjectStore {
    val objects = ConcurrentHashMap<String, ByteArray>()

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        objects[key] = bytes.copyOf()
    }

    override suspend fun get(key: String): ByteArray? = objects[key]?.copyOf()
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

    val minio: MinioContainer by lazy { MinioContainer().apply { start() } }
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
        admin = AdminConfig(TEST_ADMIN_TOKEN),
    )

/** Admin token of [testConfig]. */
const val TEST_ADMIN_TOKEN = "test-admin-token-with-at-least-32-chars"

/**
 * Server dependencies on the shared containers with a test clock, a recording OTP provider and an in-memory
 * object store (unless [objectStore] is given); state is wiped by [reset].
 */
class TestServer(
    val objectStore: ObjectStore = InMemoryObjectStore(),
    config: AppConfig = testConfig(),
) {
    val clock = MutableClock()
    val otp = RecordingOtpProvider()
    val deps = ServerDependencies(config, TestInfra.database, TestInfra.redis, otp, clock, objectStore)

    /** Empties Redis and every table so tests don't see each other's codes, limits, users or reports. */
    suspend fun reset() {
        TestInfra.redis.evalLong("redis.call('FLUSHALL') return 1", emptyList())
        TestInfra.database.query { exec("TRUNCATE ${allTables.joinToString { it.tableName }} RESTART IDENTITY CASCADE") }
        (objectStore as? InMemoryObjectStore)?.objects?.clear()
    }
}
