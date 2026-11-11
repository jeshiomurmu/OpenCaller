package io.github.jeshiomurmu.opencaller.server

import io.github.jeshiomurmu.opencaller.server.admin.web.AdminAuditLog
import io.github.jeshiomurmu.opencaller.server.admin.web.AdminWebServices
import io.github.jeshiomurmu.opencaller.server.admin.web.ImportTools
import io.github.jeshiomurmu.opencaller.server.assistant.AssistantCallService
import io.github.jeshiomurmu.opencaller.server.assistant.AssistantLineService
import io.github.jeshiomurmu.opencaller.server.assistant.AssistantRouteServices
import io.github.jeshiomurmu.opencaller.server.auth.AuthRepository
import io.github.jeshiomurmu.opencaller.server.auth.AuthService
import io.github.jeshiomurmu.opencaller.server.auth.TokenService
import io.github.jeshiomurmu.opencaller.server.auth.otp.LogOtpProvider
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpProvider
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpStore
import io.github.jeshiomurmu.opencaller.server.auth.otp.TwilioOtpProvider
import io.github.jeshiomurmu.opencaller.server.auth.otp.TwilioVoiceOtpProvider
import io.github.jeshiomurmu.opencaller.server.billing.BillingRecheckResult
import io.github.jeshiomurmu.opencaller.server.billing.BillingService
import io.github.jeshiomurmu.opencaller.server.billing.FeatureInfo
import io.github.jeshiomurmu.opencaller.server.billing.GooglePlayDeveloperApi
import io.github.jeshiomurmu.opencaller.server.billing.PlaySubscriptions
import io.github.jeshiomurmu.opencaller.server.business.BusinessDirectory
import io.github.jeshiomurmu.opencaller.server.business.ClaimCodes
import io.github.jeshiomurmu.opencaller.server.business.ClaimReview
import io.github.jeshiomurmu.opencaller.server.business.ClaimService
import io.github.jeshiomurmu.opencaller.server.config.AppConfig
import io.github.jeshiomurmu.opencaller.server.config.Environment
import io.github.jeshiomurmu.opencaller.server.config.OtpProviderKind
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.disputes.DisputeService
import io.github.jeshiomurmu.opencaller.server.family.ExpiryJob
import io.github.jeshiomurmu.opencaller.server.family.ExpiryResult
import io.github.jeshiomurmu.opencaller.server.family.FamilyAlertService
import io.github.jeshiomurmu.opencaller.server.family.FamilyService
import io.github.jeshiomurmu.opencaller.server.flash.FlashService
import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.jobs.JobRunner
import io.github.jeshiomurmu.opencaller.server.jobs.JobScheduler
import io.github.jeshiomurmu.opencaller.server.jobs.LeaderJob
import io.github.jeshiomurmu.opencaller.server.lists.ListBuildResult
import io.github.jeshiomurmu.opencaller.server.lists.ListBuilder
import io.github.jeshiomurmu.opencaller.server.lists.ListService
import io.github.jeshiomurmu.opencaller.server.lookup.LookupService
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationService
import io.github.jeshiomurmu.opencaller.server.portal.PortalService
import io.github.jeshiomurmu.opencaller.server.profile.ContactRequestService
import io.github.jeshiomurmu.opencaller.server.profile.Entitlements
import io.github.jeshiomurmu.opencaller.server.profile.ProfileService
import io.github.jeshiomurmu.opencaller.server.profile.RetentionJob
import io.github.jeshiomurmu.opencaller.server.profile.RetentionResult
import io.github.jeshiomurmu.opencaller.server.profile.StoredTierEntitlements
import io.github.jeshiomurmu.opencaller.server.profile.VisitorService
import io.github.jeshiomurmu.opencaller.server.push.HttpPushSender
import io.github.jeshiomurmu.opencaller.server.push.PushEndpointPolicy
import io.github.jeshiomurmu.opencaller.server.push.PushSender
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.recording.RecordingAgentService
import io.github.jeshiomurmu.opencaller.server.recording.RecordingCodes
import io.github.jeshiomurmu.opencaller.server.recording.RecordingCountryPolicy
import io.github.jeshiomurmu.opencaller.server.recording.RecordingLineService
import io.github.jeshiomurmu.opencaller.server.recording.RecordingRetentionJob
import io.github.jeshiomurmu.opencaller.server.recording.RecordingRetentionResult
import io.github.jeshiomurmu.opencaller.server.recording.RecordingRouteServices
import io.github.jeshiomurmu.opencaller.server.recording.RecordingService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import io.github.jeshiomurmu.opencaller.server.registry.ImportJobResult
import io.github.jeshiomurmu.opencaller.server.registry.ImportRunLog
import io.github.jeshiomurmu.opencaller.server.registry.Importer
import io.github.jeshiomurmu.opencaller.server.registry.OtpSourceCuration
import io.github.jeshiomurmu.opencaller.server.registry.SenderIdImportJob
import io.github.jeshiomurmu.opencaller.server.registry.SenderIdStore
import io.github.jeshiomurmu.opencaller.server.registry.csvImportersIn
import io.github.jeshiomurmu.opencaller.server.registry.placeholderRegistries
import io.github.jeshiomurmu.opencaller.server.reports.ReportService
import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamFeedService
import io.github.jeshiomurmu.opencaller.server.scoring.ScoringJob
import io.github.jeshiomurmu.opencaller.server.scoring.ScoringResult
import io.github.jeshiomurmu.opencaller.server.search.SearchService
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.S3ObjectStore
import io.github.jeshiomurmu.opencaller.server.sync.SeedData
import io.github.jeshiomurmu.opencaller.server.sync.SyncService
import io.github.jeshiomurmu.opencaller.server.unlist.UnlistService
import io.github.jeshiomurmu.opencaller.server.video.VideoCallerIdService
import io.github.jeshiomurmu.opencaller.server.voip.VoipCleanupJob
import io.github.jeshiomurmu.opencaller.server.voip.VoipCleanupResult
import io.github.jeshiomurmu.opencaller.server.voip.VoipRouteServices
import io.github.jeshiomurmu.opencaller.server.voip.VoipService
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration

/** How one-time codes reach a phone: [sms] always, [voice] (a call reading the code) only if configured. */
class CodeSenders(
    val sms: OtpProvider,
    val voice: OtpProvider? = null,
)

/**
 * Everything the server needs, built once in `main` (and by tests with their own clock, OTP providers, object store
 * and Play API, hence the parameter count).
 */
@Suppress("LongParameterList")
class ServerDependencies(
    val config: AppConfig,
    val database: ServerDatabase,
    val redis: Redis,
    senders: CodeSenders,
    val clock: Clock,
    val objectStore: ObjectStore = S3ObjectStore(config.minio, clock),
    playApi: PlaySubscriptions? = null,
    pushSender: PushSender? = null,
) : Closeable {
    private val otpProvider = senders.sms
    private val voiceProvider = senders.voice
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
    val entitlements: Entitlements = StoredTierEntitlements(database, config.billing.selfHosted)
    private val playSubscriptions: PlaySubscriptions? = config.billing.googlePlay?.let { playApi ?: GooglePlayDeveloperApi(it, clock) }
    val billing = BillingService(database, config.billing, playSubscriptions, rateLimiter, clock)
    val profiles = ProfileService(database, objectStore, clock)
    val visitors = VisitorService(database, objectStore, entitlements, rateLimiter, clock)
    val contactRequests = ContactRequestService(database, rateLimiter, clock)
    val retentionJob = RetentionJob(database, objectStore, clock)
    private val claimStore = OtpStore(redis, keyPrefix = "otp-claim:")
    val claims =
        ClaimService(
            db = database,
            codes =
                ClaimCodes(
                    sms = OtpService(claimStore, otpProvider, config.otp.pepper, random),
                    voice = voiceProvider?.let { OtpService(claimStore, it, config.otp.pepper, random) },
                ),
            objects = objectStore,
            rateLimiter = rateLimiter,
            clock = clock,
        )
    val claimReview = ClaimReview(database, objectStore, clock)
    val businessDirectory = BusinessDirectory(database, objectStore)
    val otpCuration = OtpSourceCuration(database, clock)
    val senderIdStore = SenderIdStore(database, clock)
    val importRuns = ImportRunLog(database, clock)
    val adminAudit = AdminAuditLog(database, clock)

    // Phase 11: push, app calls, Flash messages and video caller ID (ADR 0007).
    val push =
        PushService(
            db = database,
            sender = pushSender ?: HttpPushSender(config.voip.push.fcm, config.voip.push.webPush, clock),
            policy = PushEndpointPolicy(allowPrivate = config.environment != Environment.PRODUCTION),
            clock = clock,
            enabled = config.voip.push.enabled || pushSender != null,
        )
    val voip = VoipService(database, config.voip.liveKit, push, rateLimiter, clock, random)
    val flash = FlashService(database, push, rateLimiter, clock)
    val video = VideoCallerIdService(database, objectStore, rateLimiter, clock)

    // Phase 12: family protection, the scam feed and the business dashboard (ADR 0008).
    val family = FamilyService(database, rateLimiter, clock, random)
    val familyAlerts = FamilyAlertService(database, push, rateLimiter, clock)
    val scamFeed = ScamFeedService(database, clock)
    val portal =
        PortalService(
            db = database,
            otp = OtpService(OtpStore(redis, keyPrefix = "otp-portal:"), otpProvider, config.otp.pepper, random),
            rateLimiter = rateLimiter,
            clock = clock,
            random = random,
        )
    val expiryJob = ExpiryJob(database, clock)

    // Phase 13: the recording line (ADR 0009).
    val recordingCountries = RecordingCountryPolicy(database, config.recording, clock)
    val recordings = RecordingService(database, objectStore, config.recording, clock)
    val recording =
        RecordingRouteServices(
            lines =
                RecordingLineService(
                    db = database,
                    countries = recordingCountries,
                    entitlements = entitlements,
                    rateLimiter = rateLimiter,
                    clock = clock,
                    codes = RecordingCodes(config.otp.pepper, random),
                ),
            recordings = recordings,
            agents = RecordingAgentService(database, objectStore, push, clock),
            agentToken = config.recording.agentToken,
        )
    val recordingRetention = RecordingRetentionJob(database, objectStore, config.recording, clock)

    // Phase 13: the call assistant (ADR 0010).
    val assistant =
        AssistantRouteServices(
            lines = AssistantLineService(database, config.assistant, entitlements, rateLimiter, clock),
            calls = AssistantCallService(database, config.assistant, config.voip.liveKit, entitlements, push, rateLimiter, clock),
            agentToken = config.recording.agentToken,
        )

    val voipRoutes =
        VoipRouteServices(push, NotificationService(push, voip, flash, familyAlerts, recordings, assistant.calls), voip, flash, video)
    val voipCleanup = VoipCleanupJob(database, clock)

    /** What `GET /v1/server` says about the Phase 11 features. */
    val features =
        FeatureInfo(
            appCalls = voip.enabled,
            flash = flash.enabled,
            fcm = config.voip.push.fcm != null,
            webPushVapidKey =
                config.voip.push.webPush
                    ?.publicKeyBase64Url,
            assistant = assistant.lines.enabled && config.voip.liveKit != null,
        )

    /** Sender-ID importers: the CSV extracts in the import folder, then the registries without documented access. */
    val importers: () -> List<Importer<*>> = { csvImportersIn(config.imports.dir, senderIdStore) + placeholderRegistries }
    val importJob = SenderIdImportJob(importers, importRuns, clock)

    /** What the admin web works with. */
    val adminWeb =
        AdminWebServices(
            claimReview,
            otpCuration,
            ImportTools(senderIdStore, importRuns, importers),
            adminAudit,
            clock,
            scamFeed,
            recordingCountries,
        )

    val scoringJob = ScoringJob(database, config.scoring, clock)
    val listBuilder = ListBuilder(database, objectStore, config.lists, clock)
    val jobRunner = JobRunner(database, meterRegistry, clock)

    /** Background jobs, each under its own advisory lock. */
    val jobs: List<LeaderJob> =
        listOf(
            LeaderJob(ScoringJob.JOB_NAME, SCORING_LOCK) { ApiJson.encodeToJsonElement(ScoringResult.serializer(), scoringJob.run()) },
            LeaderJob(LISTS_JOB, LISTS_LOCK) { ApiJson.encodeToJsonElement(ListBuildResult.serializer(), listBuilder.build()) },
            LeaderJob(RetentionJob.JOB_NAME, RETENTION_LOCK) {
                ApiJson.encodeToJsonElement(RetentionResult.serializer(), retentionJob.run())
            },
            LeaderJob(
                SenderIdImportJob.JOB_NAME,
                IMPORT_LOCK,
            ) { ApiJson.encodeToJsonElement(ImportJobResult.serializer(), importJob.run()) },
            LeaderJob(BILLING_JOB, BILLING_LOCK) { ApiJson.encodeToJsonElement(BillingRecheckResult.serializer(), billing.recheck()) },
            LeaderJob(VoipCleanupJob.JOB_NAME, VOIP_CLEANUP_LOCK) {
                ApiJson.encodeToJsonElement(VoipCleanupResult.serializer(), voipCleanup.run())
            },
            LeaderJob(ExpiryJob.JOB_NAME, EXPIRY_LOCK) { ApiJson.encodeToJsonElement(ExpiryResult.serializer(), expiryJob.run()) },
            LeaderJob(RecordingRetentionJob.JOB_NAME, RECORDING_RETENTION_LOCK) {
                ApiJson.encodeToJsonElement(RecordingRetentionResult.serializer(), recordingRetention.run())
            },
        )

    /**
     * Starts the scoring job every [ScoringConfig.intervalMinutes]; the list build, retention and sender-ID imports
     * daily; the billing re-check, the VoIP cleanup and the Phase 12 expiry hourly; recording retention every 10 minutes.
     */
    fun startJobs(scope: CoroutineScope) {
        val scheduler = JobScheduler(scope, jobRunner, clock)
        scheduler.every(Duration.ofMinutes(config.scoring.intervalMinutes.toLong()), jobs.first { it.name == ScoringJob.JOB_NAME })
        scheduler.dailyAt(config.lists.buildHourUtc, jobs.first { it.name == LISTS_JOB })
        scheduler.dailyAt(RetentionJob.HOUR_UTC, jobs.first { it.name == RetentionJob.JOB_NAME })
        scheduler.dailyAt(config.imports.hourUtc, jobs.first { it.name == SenderIdImportJob.JOB_NAME })
        scheduler.every(Duration.ofHours(1), jobs.first { it.name == BILLING_JOB })
        scheduler.every(Duration.ofHours(1), jobs.first { it.name == VoipCleanupJob.JOB_NAME })
        scheduler.every(Duration.ofHours(1), jobs.first { it.name == ExpiryJob.JOB_NAME })
        scheduler.every(
            Duration.ofMinutes(RecordingRetentionJob.INTERVAL_MINUTES),
            jobs.first { it.name == RecordingRetentionJob.JOB_NAME },
        )
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

        /** Name of the billing re-check job. */
        const val BILLING_JOB = "billing"
        private const val SCORING_LOCK = 4_000_001L
        private const val LISTS_LOCK = 4_000_002L
        private const val RETENTION_LOCK = 4_000_003L
        private const val IMPORT_LOCK = 4_000_004L
        private const val BILLING_LOCK = 4_000_005L
        private const val VOIP_CLEANUP_LOCK = 4_000_006L
        private const val EXPIRY_LOCK = 4_000_007L
        private const val RECORDING_RETENTION_LOCK = 4_000_008L

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
            // Codes by phone call: Twilio with TWILIO_VOICE_FROM set; in development the log provider stands in.
            val voiceProvider =
                when (config.otp.provider) {
                    OtpProviderKind.LOG -> otpProvider
                    OtpProviderKind.TWILIO -> config.twilio?.takeIf { it.voiceFrom != null }?.let(::TwilioVoiceOtpProvider)
                }
            val database = ServerDatabase.connect(config.database, io)
            val redis =
                try {
                    Redis.connect(config.redis.url, config.redis.password)
                } catch (e: io.lettuce.core.RedisException) {
                    database.close()
                    throw e
                }
            return ServerDependencies(config, database, redis, CodeSenders(otpProvider, voiceProvider), Clock.systemUTC())
        }
    }
}
