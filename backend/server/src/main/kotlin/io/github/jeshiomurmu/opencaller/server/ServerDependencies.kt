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
import io.github.jeshiomurmu.opencaller.server.disputes.DisputeService
import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.jobs.JobRunner
import io.github.jeshiomurmu.opencaller.server.jobs.JobScheduler
import io.github.jeshiomurmu.opencaller.server.jobs.LeaderJob
import io.github.jeshiomurmu.opencaller.server.lists.ListBuildResult
import io.github.jeshiomurmu.opencaller.server.lists.ListBuilder
import io.github.jeshiomurmu.opencaller.server.lists.ListService
import io.github.jeshiomurmu.opencaller.server.lookup.LookupService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import io.github.jeshiomurmu.opencaller.server.reports.ReportService
import io.github.jeshiomurmu.opencaller.server.scoring.ScoringJob
import io.github.jeshiomurmu.opencaller.server.scoring.ScoringResult
import io.github.jeshiomurmu.opencaller.server.search.SearchService
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.S3ObjectStore
import io.github.jeshiomurmu.opencaller.server.sync.SeedData
import io.github.jeshiomurmu.opencaller.server.sync.SyncService
import io.github.jeshiomurmu.opencaller.server.unlist.UnlistService
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration

/** Everything the server needs, built once in `main` (and by tests with their own clock and OTP provider). */
class ServerDependencies(
    val config: AppConfig,
    val database: ServerDatabase,
    val redis: Redis,
    otpProvider: OtpProvider,
    val clock: Clock,
    val objectStore: ObjectStore = S3ObjectStore(config.minio, clock),
) : Closeable {
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val random = SecureRandom()
    private val rateLimiter = RateLimiter(redis)
    val tokens = TokenService(config.jwt, clock, random)
    val auth =
        AuthService(
            otp = OtpService(OtpStore(redis), otpProvider, config.otp.pepper, random),
            tokens = tokens,
            repository = AuthRepository(database, clock),
            rateLimiter = rateLimiter,
        )
    val reports = ReportService(database, rateLimiter, clock)
    val lookup = LookupService(database)
    val search = SearchService(database, rateLimiter, clock)
    val lists = ListService(database, objectStore)
    val disputes = DisputeService(database, rateLimiter, clock)
    val unlist =
        UnlistService(
            otp = OtpService(OtpStore(redis, keyPrefix = "otp-unlist:"), otpProvider, config.otp.pepper, random),
            rateLimiter = rateLimiter,
            db = database,
            clock = clock,
            processedWithinMinutes = config.scoring.intervalMinutes,
        )
    val sync = SyncService(database, SeedData.load())

    val scoringJob = ScoringJob(database, config.scoring, clock)
    val listBuilder = ListBuilder(database, objectStore, config.lists, clock)
    val jobRunner = JobRunner(database, meterRegistry, clock)

    /** Background jobs, each under its own advisory lock. */
    val jobs: List<LeaderJob> =
        listOf(
            LeaderJob(ScoringJob.JOB_NAME, SCORING_LOCK) { ApiJson.encodeToJsonElement(ScoringResult.serializer(), scoringJob.run()) },
            LeaderJob(LISTS_JOB, LISTS_LOCK) { ApiJson.encodeToJsonElement(ListBuildResult.serializer(), listBuilder.build()) },
        )

    /** Starts the scoring job every [ScoringConfig.intervalMinutes] and the list build nightly. */
    fun startJobs(scope: CoroutineScope) {
        val scheduler = JobScheduler(scope, jobRunner, clock)
        scheduler.every(Duration.ofMinutes(config.scoring.intervalMinutes.toLong()), jobs.first { it.name == ScoringJob.JOB_NAME })
        scheduler.dailyAt(config.lists.buildHourUtc, jobs.first { it.name == LISTS_JOB })
    }

    override fun close() {
        redis.close()
        database.close()
        meterRegistry.close()
    }

    /** Job names and lock keys; [create] connects everything. */
    companion object {
        /** Name of the list-build job. */
        const val LISTS_JOB = "lists"
        private const val SCORING_LOCK = 4_000_001L
        private const val LISTS_LOCK = 4_000_002L

        /** Connects to PostgreSQL (running migrations) and Redis, picks the OTP provider and seeds sender IDs. */
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
