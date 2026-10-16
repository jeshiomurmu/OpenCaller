package io.github.jeshiomurmu.opencaller.server.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValueFactory

/** Where the server runs. [PRODUCTION] refuses development-only parts such as the log OTP provider. */
enum class Environment {
    DEVELOPMENT,
    TEST,
    PRODUCTION,
}

/** Which [io.github.jeshiomurmu.opencaller.server.auth.otp.OtpProvider] sends one-time codes. */
enum class OtpProviderKind {
    LOG,
    TWILIO,
}

/** Complete, validated server configuration (resources/application.conf, filled from environment variables). */
data class AppConfig(
    val environment: Environment,
    val version: String,
    val http: HttpConfig,
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val minio: MinioConfig,
    val jwt: JwtConfig,
    val otp: OtpConfig,
    val twilio: TwilioConfig?,
)

/** Public API port and the internal metrics port. */
data class HttpConfig(
    val port: Int,
    val metricsPort: Int,
)

/** PostgreSQL connection. */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    override fun toString() = "DatabaseConfig(url=$url, user=$user, password=***, maxPoolSize=$maxPoolSize)"
}

/** Redis connection: URI plus an optional password (kept out of the URI). */
data class RedisConfig(
    val url: String,
    val password: String? = null,
) {
    override fun toString() = "RedisConfig(url=$url, password=${if (password == null) "none" else "***"})"
}

/** MinIO / S3-compatible object storage (used from the list builder on; validated now so deployments fail early). */
data class MinioConfig(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
) {
    override fun toString() = "MinioConfig(endpoint=$endpoint, accessKey=***, secretKey=***)"
}

/** Access-token signing. */
data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
) {
    override fun toString() = "JwtConfig(secret=***, issuer=$issuer, audience=$audience)"
}

/** One-time codes: hashing secret and delivery provider. */
data class OtpConfig(
    val pepper: String,
    val provider: OtpProviderKind,
) {
    override fun toString() = "OtpConfig(pepper=***, provider=$provider)"
}

/** Twilio Programmable Messaging; exactly one of [from] and [messagingServiceSid] is set. */
data class TwilioConfig(
    val accountSid: String,
    val authToken: String,
    val from: String?,
    val messagingServiceSid: String?,
    val baseUrl: String,
) {
    override fun toString() = "TwilioConfig(accountSid=$accountSid, authToken=***, from=$from, messagingServiceSid=$messagingServiceSid)"
}

/** The configuration is missing values or has invalid ones; [problems] names each environment variable. */
class ConfigException(
    val problems: List<String>,
) : RuntimeException("Invalid server configuration:\n" + problems.joinToString("\n") { "  - $it" })

/**
 * Reads resources/application.conf, substituting `${?VAR}` from [env] (the process environment in production,
 * a map in tests), and validates it. Throws [ConfigException] listing every problem at once.
 */
fun loadConfig(env: Map<String, String>): AppConfig {
    // [env] sits under the file as plain keys (fromMap doesn't split names on dots), so `${?DB_URL}` finds it;
    // the process environment is never read implicitly, which keeps tests independent of the machine.
    val raw =
        ConfigFactory
            .parseResources("application.conf")
            .withFallback(ConfigValueFactory.fromMap(env))
            .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
            .getConfig("opencaller")
    return ConfigReader(raw).read()
}

/** Reads typed values and collects problems instead of failing on the first one. */
private class ConfigReader(
    private val config: Config,
) {
    private val problems = mutableListOf<String>()

    fun read(): AppConfig {
        val environment = enumValue<Environment>("environment", "ENV")
        val otpProvider = enumValue<OtpProviderKind>("otp.provider", "OTP_PROVIDER")
        if (environment == Environment.PRODUCTION && otpProvider == OtpProviderKind.LOG) {
            problems += "OTP_PROVIDER=log is for development only and is refused when ENV=production"
        }
        val result =
            AppConfig(
                environment = environment ?: Environment.DEVELOPMENT,
                version = string("version").orEmpty(),
                http = HttpConfig(int("http.port", "PORT", 1..MAX_PORT), int("http.metricsPort", "METRICS_PORT", 1..MAX_PORT)),
                database = readDatabase(),
                redis = RedisConfig(prefixed("redis.url", "REDIS_URL", "redis://", "rediss://"), string("redis.password")),
                minio = readMinio(),
                jwt =
                    JwtConfig(
                        secret("jwt.secret", "JWT_SECRET"),
                        required("jwt.issuer", "JWT_ISSUER"),
                        required("jwt.audience", "JWT_AUDIENCE"),
                    ),
                otp = OtpConfig(secret("otp.pepper", "OTP_PEPPER"), otpProvider ?: OtpProviderKind.LOG),
                twilio = if (otpProvider == OtpProviderKind.TWILIO) readTwilio() else null,
            )
        if (result.http.port == result.http.metricsPort) problems += "PORT and METRICS_PORT must differ"
        if (problems.isNotEmpty()) throw ConfigException(problems.toList())
        return result
    }

    private fun readDatabase() =
        DatabaseConfig(
            url = prefixed("database.url", "DB_URL", "jdbc:postgresql://"),
            user = required("database.user", "DB_USER"),
            password = required("database.password", "DB_PASSWORD"),
            maxPoolSize = int("database.maxPoolSize", "DB_MAX_POOL_SIZE", 1..MAX_POOL_SIZE),
        )

    private fun readMinio() =
        MinioConfig(
            endpoint = prefixed("minio.endpoint", "MINIO_ENDPOINT", "http://", "https://"),
            accessKey = required("minio.accessKey", "MINIO_ACCESS_KEY"),
            secretKey = required("minio.secretKey", "MINIO_SECRET_KEY"),
        )

    private fun readTwilio(): TwilioConfig {
        val from = string("twilio.from")
        val service = string("twilio.messagingServiceSid")
        if ((from == null) == (service == null)) {
            problems += "set exactly one of TWILIO_FROM and TWILIO_MESSAGING_SERVICE_SID when OTP_PROVIDER=twilio"
        }
        return TwilioConfig(
            accountSid = required("twilio.accountSid", "TWILIO_ACCOUNT_SID"),
            authToken = required("twilio.authToken", "TWILIO_AUTH_TOKEN"),
            from = from,
            messagingServiceSid = service,
            baseUrl = prefixed("twilio.baseUrl", "TWILIO_BASE_URL", "https://", "http://"),
        )
    }

    // An unset or blank variable counts as missing.
    private fun string(path: String): String? = if (config.hasPath(path)) config.getString(path).trim().ifEmpty { null } else null

    private fun required(
        path: String,
        envVar: String,
    ): String = string(path) ?: "".also { problems += "$envVar is required" }

    private fun secret(
        path: String,
        envVar: String,
    ): String {
        val value = required(path, envVar)
        if (value.isNotEmpty() && value.length < MIN_SECRET_LENGTH) problems += "$envVar must be at least $MIN_SECRET_LENGTH characters"
        return value
    }

    private fun prefixed(
        path: String,
        envVar: String,
        vararg prefixes: String,
    ): String {
        val value = required(path, envVar)
        if (value.isNotEmpty() && prefixes.none { value.startsWith(it) }) {
            problems += "$envVar must start with ${prefixes.joinToString(" or ")}"
        }
        return value
    }

    private fun int(
        path: String,
        envVar: String,
        range: IntRange,
    ): Int {
        val value = string(path)?.toIntOrNull()
        if (value == null || value !in range) {
            problems += "$envVar must be a number in ${range.first}..${range.last}"
            return range.first
        }
        return value
    }

    private inline fun <reified E : Enum<E>> enumValue(
        path: String,
        envVar: String,
    ): E? {
        val value = string(path)
        val allowed = enumValues<E>().map { it.name.lowercase() }
        return when {
            value == null -> null.also { problems += "$envVar is required (${allowed.joinToString(" | ")})" }
            value.lowercase() !in allowed -> null.also { problems += "$envVar must be one of ${allowed.joinToString(" | ")}" }
            else -> enumValues<E>().first { it.name.equals(value, ignoreCase = true) }
        }
    }

    private companion object {
        const val MIN_SECRET_LENGTH = 32
        const val MAX_PORT = 65_535
        const val MAX_POOL_SIZE = 200
    }
}
