package io.github.jeshiomurmu.opencaller.server

import io.github.jeshiomurmu.opencaller.server.auth.AuthRepository
import io.github.jeshiomurmu.opencaller.server.auth.AuthService
import io.github.jeshiomurmu.opencaller.server.auth.TokenService
import io.github.jeshiomurmu.opencaller.server.auth.otp.LogOtpProvider
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpProvider
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpStore
import io.github.jeshiomurmu.opencaller.server.auth.otp.TwilioOtpProvider
import io.github.jeshiomurmu.opencaller.server.config.AppConfig
import io.github.jeshiomurmu.opencaller.server.config.OtpProviderKind
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import java.io.Closeable
import java.security.SecureRandom
import java.time.Clock

/** Everything the server needs, built once in `main` (and by tests with their own clock and OTP provider). */
class ServerDependencies(
    val config: AppConfig,
    val database: ServerDatabase,
    val redis: Redis,
    otpProvider: OtpProvider,
    clock: Clock,
    val meterRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) : Closeable {
    private val random = SecureRandom()
    val tokens = TokenService(config.jwt, clock, random)
    val auth =
        AuthService(
            otp = OtpService(OtpStore(redis), otpProvider, config.otp.pepper, random),
            tokens = tokens,
            repository = AuthRepository(database, clock),
            rateLimiter = RateLimiter(redis),
        )

    override fun close() {
        redis.close()
        database.close()
        meterRegistry.close()
    }

    /** Connects to PostgreSQL (running migrations) and Redis and picks the configured OTP provider. */
    companion object {
        fun create(
            config: AppConfig,
            io: CoroutineDispatcher,
        ): ServerDependencies {
            val otpProvider =
                when (config.otp.provider) {
                    OtpProviderKind.LOG -> LogOtpProvider(config.environment)
                    OtpProviderKind.TWILIO -> TwilioOtpProvider(requireNotNull(config.twilio) { "TWILIO_* settings missing" })
                }
            val database = ServerDatabase.connect(config.database, io)
            val redis =
                try {
                    Redis.connect(config.redis.url, config.redis.password)
                } catch (e: io.lettuce.core.RedisException) {
                    database.close()
                    throw e
                }
            return ServerDependencies(config, database, redis, otpProvider, Clock.systemUTC())
        }
    }
}
