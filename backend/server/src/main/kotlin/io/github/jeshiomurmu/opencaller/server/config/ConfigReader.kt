package io.github.jeshiomurmu.opencaller.server.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValueFactory
import java.nio.file.Path

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

/**
 * Reads typed values and collects problems instead of failing on the first one. One small reader per value type
 * and section keeps each check next to the variable it names, hence the many functions.
 */
@Suppress("TooManyFunctions")
internal class ConfigReader(
    private val config: Config,
) {
    internal val problems = mutableListOf<String>()

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
                scoring = readScoring(),
                lists =
                    ListConfig(
                        int("lists.buildHourUtc", "LIST_BUILD_HOUR_UTC", 0..LAST_HOUR),
                        double("lists.minScore", "LIST_MIN_SCORE"),
                        int("lists.standardMaxEntries", "LIST_STANDARD_MAX_ENTRIES", 1..MAX_LIST_ENTRIES),
                    ),
                admin = AdminConfig(string("admin.token")?.also { checkSecretLength(it, "ADMIN_TOKEN") }),
                adminWeb = readAdminWeb(),
                imports = ImportConfig(string("imports.dir"), int("imports.hourUtc", "IMPORT_HOUR_UTC", 0..LAST_HOUR)),
                billing = BillingConfig(bool("billing.selfHosted", "SELF_HOSTED"), readGooglePlay(environment)),
                voip = readVoip(environment),
            )
        if (result.scoring.verifiedCap >= result.lists.minScore) problems += "SCORING_VERIFIED_CAP must be below LIST_MIN_SCORE"
        if (result.http.port == result.http.metricsPort) problems += "PORT and METRICS_PORT must differ"
        result.adminWeb?.port?.let { port ->
            if (port == result.http.port ||
                port == result.http.metricsPort
            ) {
                problems += "ADMIN_WEB_PORT must differ from PORT and METRICS_PORT"
            }
        }
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

    private fun readMinio(): MinioConfig {
        val bucket = required("minio.bucket", "MINIO_BUCKET")
        if (bucket.isNotEmpty() && !BUCKET_NAME.matches(bucket)) problems += "MINIO_BUCKET must be a valid S3 bucket name"
        val endpoint = prefixed("minio.endpoint", "MINIO_ENDPOINT", "http://", "https://")
        return MinioConfig(
            endpoint = endpoint,
            accessKey = required("minio.accessKey", "MINIO_ACCESS_KEY"),
            secretKey = required("minio.secretKey", "MINIO_SECRET_KEY"),
            bucket = bucket,
            region = required("minio.region", "MINIO_REGION"),
            publicEndpoint =
                string("minio.publicEndpoint")
                    ?.let { prefixed("minio.publicEndpoint", "MINIO_PUBLIC_ENDPOINT", "http://", "https://") }
                    ?: endpoint,
        )
    }

    // The admin web is off unless ADMIN_WEB_PORT is set; then a user, a long password and an allowlist are required.
    private fun readAdminWeb(): AdminWebConfig? {
        if (string("adminWeb.port") == null) return null
        val port = int("adminWeb.port", "ADMIN_WEB_PORT", 1..MAX_PORT)
        val user = required("adminWeb.user", "ADMIN_WEB_USER")
        if (user.isNotEmpty() &&
            !ADMIN_USER.matches(user)
        ) {
            problems += "ADMIN_WEB_USER must be 1 to 32 letters, digits, dots, dashes or underscores"
        }
        val password = secret("adminWeb.password", "ADMIN_WEB_PASSWORD")
        val networks =
            required("adminWeb.allowedNetworks", "ADMIN_WEB_ALLOWED_NETWORKS")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { cidr ->
                    IpNetwork.parse(cidr) ?: null.also { problems += "ADMIN_WEB_ALLOWED_NETWORKS has an invalid CIDR block: $cidr" }
                }
        return AdminWebConfig(port, user, password, networks)
    }

    // Play billing is on when a service-account key file is configured; then the package, product and secret are required.
    private fun readGooglePlay(environment: Environment?): GooglePlayConfig? {
        val keyFile = string("billing.googlePlay.serviceAccountFile") ?: return null
        val key =
            try {
                ServiceAccountKey.read(Path.of(keyFile))
            } catch (e: IllegalArgumentException) {
                problems += "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE ${e.message}"
                null
            }
        if (key != null && environment == Environment.PRODUCTION && !key.tokenUri.startsWith("https://")) {
            problems += "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE: token_uri must use https in production"
        }
        val packageName = required("billing.googlePlay.packageName", "GOOGLE_PLAY_PACKAGE_NAME")
        if (packageName.isNotEmpty() &&
            !PACKAGE_NAME.matches(packageName)
        ) {
            problems += "GOOGLE_PLAY_PACKAGE_NAME must be an Android package name"
        }
        val productId = required("billing.googlePlay.productId", "GOOGLE_PLAY_PRODUCT_ID")
        if (productId.isNotEmpty() && !PRODUCT_ID.matches(productId)) problems += "GOOGLE_PLAY_PRODUCT_ID must be a Play product ID"
        val secret = secret("billing.googlePlay.accountSecret", "BILLING_ACCOUNT_SECRET")
        val baseUrl = prefixed("billing.googlePlay.apiBaseUrl", "GOOGLE_PLAY_API_BASE_URL", "https://", "http://")
        return key?.let { GooglePlayConfig(packageName, productId, it, secret, baseUrl.trimEnd('/')) }
    }

    private fun readScoring() =
        ScoringConfig(
            intervalMinutes = int("scoring.intervalMinutes", "SCORING_INTERVAL_MINUTES", 1..MAX_INTERVAL_MINUTES),
            priorSpam = double("scoring.priorSpam", "SCORING_PRIOR_SPAM", MIN_PRIOR..MAX_PRIOR),
            priorNotSpam = double("scoring.priorNotSpam", "SCORING_PRIOR_NOT_SPAM", MIN_PRIOR..MAX_PRIOR),
            halfLifeDays = double("scoring.halfLifeDays", "SCORING_HALF_LIFE_DAYS", 1.0..MAX_HALF_LIFE_DAYS),
            verifiedCap = double("scoring.verifiedCap", "SCORING_VERIFIED_CAP"),
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
            voiceFrom =
                string("twilio.voiceFrom")?.also {
                    if (!E164_NUMBER.matches(it)) problems += "TWILIO_VOICE_FROM must be an E.164 number such as +12015550100"
                },
        )
    }

    // An unset or blank variable counts as missing.
    internal fun string(path: String): String? = if (config.hasPath(path)) config.getString(path).trim().ifEmpty { null } else null

    internal fun required(
        path: String,
        envVar: String,
    ): String = string(path) ?: "".also { problems += "$envVar is required" }

    internal fun secret(
        path: String,
        envVar: String,
    ): String = required(path, envVar).also { if (it.isNotEmpty()) checkSecretLength(it, envVar) }

    private fun checkSecretLength(
        value: String,
        envVar: String,
    ) {
        if (value.length < MIN_SECRET_LENGTH) problems += "$envVar must be at least $MIN_SECRET_LENGTH characters"
    }

    internal fun bool(
        path: String,
        envVar: String,
    ): Boolean =
        when (string(path)?.lowercase()) {
            null, "false" -> false
            "true" -> true
            else -> false.also { problems += "$envVar must be true or false" }
        }

    private fun double(
        path: String,
        envVar: String,
        range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    ): Double {
        val value = string(path)?.toDoubleOrNull()
        if (value == null || value !in range) {
            problems += "$envVar must be a number in ${range.start}..${range.endInclusive}"
            return range.start
        }
        return value
    }

    internal fun prefixed(
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

    internal fun int(
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
        const val LAST_HOUR = 23
        const val MAX_INTERVAL_MINUTES = 1440
        const val MIN_PRIOR = 0.01
        const val MAX_PRIOR = 1000.0
        const val MAX_HALF_LIFE_DAYS = 3650.0
        const val MAX_LIST_ENTRIES = 10_000_000

        val ADMIN_USER = Regex("[A-Za-z0-9._-]{1,32}")
        val E164_NUMBER = Regex("\\+[1-9][0-9]{6,14}")
        val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

        // Play product IDs: lower-case letters, digits, underscores and dots, starting with a letter or digit.
        val PRODUCT_ID = Regex("[a-z0-9][a-z0-9_.]{0,39}")

        // S3 bucket naming rules: 3–63 lowercase letters, digits, dots and hyphens, starting and ending alphanumeric.
        val BUCKET_NAME = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    }
}
