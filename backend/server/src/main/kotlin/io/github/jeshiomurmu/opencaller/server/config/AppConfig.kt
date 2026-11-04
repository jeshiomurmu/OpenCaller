package io.github.jeshiomurmu.opencaller.server.config

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
    val scoring: ScoringConfig = ScoringConfig(),
    val lists: ListConfig = ListConfig(),
    val admin: AdminConfig = AdminConfig(null),
    val adminWeb: AdminWebConfig? = null,
    val imports: ImportConfig = ImportConfig(),
    val billing: BillingConfig = BillingConfig(),
    val voip: VoipConfig = VoipConfig(),
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

/**
 * MinIO / S3-compatible object storage for the published spam lists and profile pictures. The server talks to
 * [endpoint]; presigned URLs for apps use [publicEndpoint] (the store's address as apps reach it), which defaults
 * to [endpoint].
 */
data class MinioConfig(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String = "opencaller",
    val region: String = "us-east-1",
    val publicEndpoint: String = endpoint,
) {
    override fun toString() =
        "MinioConfig(endpoint=$endpoint, publicEndpoint=$publicEndpoint, accessKey=***, secretKey=***, bucket=$bucket, region=$region)"
}

/**
 * Bayesian reputation (docs/architecture.md §9): priors [priorSpam] (α0) and [priorNotSpam] (β0), report weight
 * halving every [halfLifeDays]. Verified business numbers are capped at [verifiedCap] (below every spam threshold)
 * and go to the dispute queue instead. The job runs every [intervalMinutes].
 */
data class ScoringConfig(
    val intervalMinutes: Int = 15,
    val priorSpam: Double = 1.0,
    val priorNotSpam: Double = 2.0,
    val halfLifeDays: Double = 30.0,
    val verifiedCap: Double = 0.39,
)

/**
 * Spam-list builder: runs nightly at [buildHourUtc]; numbers scoring at least [minScore] are listed. A country's
 * standard list keeps its [standardMaxEntries] most-reported numbers; the rest go to its extended list (F-PR-03),
 * which only supporters download.
 */
data class ListConfig(
    val buildHourUtc: Int = 3,
    val minScore: Double = 0.4,
    val standardMaxEntries: Int = DEFAULT_STANDARD_MAX_ENTRIES,
) {
    /** Defaults. */
    companion object {
        /** About 2.8 MB of entries (14 bytes each). */
        const val DEFAULT_STANDARD_MAX_ENTRIES = 200_000
    }
}

/** Admin endpoints on the internal port (run a job now). Disabled when [token] is `null`. */
data class AdminConfig(
    val token: String?,
) {
    override fun toString() = "AdminConfig(token=${if (token == null) "disabled" else "***"})"
}

/**
 * The admin web (business-claim review, OTP-source curation, sender-ID imports) on its own [port], behind HTTP
 * Basic auth ([user], [password]) and an IP allowlist ([allowedNetworks], CIDR blocks). Off when no port is set.
 */
data class AdminWebConfig(
    val port: Int,
    val user: String,
    val password: String,
    val allowedNetworks: List<IpNetwork>,
) {
    override fun toString() = "AdminWebConfig(port=$port, user=$user, password=***, allowedNetworks=$allowedNetworks)"
}

/** Scheduled sender-ID imports: CSV extracts under [dir] (`<source>/<ISO>.csv`), read daily at [hourUtc]. */
data class ImportConfig(
    val dir: String? = null,
    val hourUtc: Int = 4,
)

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

/**
 * Twilio Programmable Messaging; exactly one of [from] and [messagingServiceSid] is set. [voiceFrom] (a Twilio
 * phone number) enables codes by phone call for business claims; without it claims verify by SMS only.
 */
data class TwilioConfig(
    val accountSid: String,
    val authToken: String,
    val from: String?,
    val messagingServiceSid: String?,
    val baseUrl: String,
    val voiceFrom: String? = null,
) {
    override fun toString() =
        "TwilioConfig(accountSid=$accountSid, authToken=***, from=$from, messagingServiceSid=$messagingServiceSid, voiceFrom=$voiceFrom)"
}

/** The configuration is missing values or has invalid ones; [problems] names each environment variable. */
class ConfigException(
    val problems: List<String>,
) : RuntimeException("Invalid server configuration:\n" + problems.joinToString("\n") { "  - $it" })
