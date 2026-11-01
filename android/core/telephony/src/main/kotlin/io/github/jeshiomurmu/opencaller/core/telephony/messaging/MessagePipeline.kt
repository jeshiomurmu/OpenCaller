package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.OtpInfo
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Writes a received SMS into Android's message store (`Telephony.Sms.Inbox`). */
fun interface SmsInbox {
    /** Stores [sms] and returns where it went. */
    suspend fun save(sms: IncomingSms): AppResult<StoredMessage>
}

/** The name the user's contacts give a sender's address, if any (on the device; never the network). */
fun interface SenderNames {
    /** The contact name of [address], or `null`. */
    suspend fun nameOf(address: String): String?
}

/** Saves [MessageMeta] rows into OpenCaller's own database. */
fun interface MessageMetaSink {
    /** Saves [meta]. */
    suspend fun save(meta: MessageMeta): AppResult<Unit>
}

/** What the notification of a stored message shows: who, the category, the code (hidden on the lock screen) and risks. */
data class ReceivedDetails(
    val senderName: String?,
    val category: MessageCategory = MessageCategory.PERSONAL,
    val otp: OtpInfo? = null,
    val risks: Set<MessageRisk> = emptySet(),
    val imitates: String? = null,
) {
    /** Never prints the code or the name. */
    override fun toString(): String = "ReceivedDetails(category=$category, otp=${otp != null}, risks=$risks)"
}

/** Tells the user about received messages. */
interface IncomingMessageNotifier {
    /** A message was stored at [message]; shows (or updates) that conversation's notification. */
    suspend fun stored(
        message: StoredMessage,
        details: ReceivedDetails,
    )

    /** Android's message store refused [sms]; shows the text itself so it isn't lost silently. */
    suspend fun unsaved(
        sms: IncomingSms,
        senderName: String?,
    )
}

/** Time limits of [MessagePipeline]; together they stay under the 10 s a broadcast receiver may take. */
data class MessagePipelineConfig(
    val storeMillis: Long = 4_000,
    val extractMillis: Long = 500,
    val senderMillis: Long = 1_000,
    val analysisMillis: Long = 1_000,
    val guardMillis: Long = 500,
    val metaMillis: Long = 1_000,
    val notifyMillis: Long = 1_500,
) {
    /** The longest the pipeline can take. */
    val totalMillis: Long get() = storeMillis + extractMillis + senderMillis + analysisMillis + guardMillis + metaMillis + notifyMillis
}

/** What became of a received SMS. */
sealed interface PipelineOutcome {
    /** Stored in Android's message store at [message] as [category]. */
    data class Stored(
        val message: StoredMessage,
        val category: MessageCategory = MessageCategory.PERSONAL,
    ) : PipelineOutcome

    /** Android's message store refused it with [error]; the user was shown the text in a notification. */
    data class Unsaved(
        val error: AppError,
    ) : PipelineOutcome
}

/**
 * The incoming-SMS pipeline of docs/architecture.md §4.4, every step time-limited: 1 store the message (as the
 * default SMS app OpenCaller must), 2 name the sender ([SenderResolver]), 3–5 one-time code, transaction, links and
 * category ([MessageAnalyzer]), 6 the OTP safety guard and burst alert ([OtpWatch]), 7 the [MessageMeta] row and the
 * notification. A failure or timeout in steps 2–6 leaves the message Personal (docs/rules.md §5). The text is never
 * written to OpenCaller's database and nothing leaves the device.
 */
@Suppress("LongParameterList") // One collaborator per pipeline step.
class MessagePipeline(
    private val inbox: SmsInbox,
    private val resolver: SenderResolver,
    private val analyzer: MessageAnalyzer,
    private val otp: OtpWatch,
    private val meta: MessageMetaSink,
    private val notifier: IncomingMessageNotifier,
    private val clock: Clock,
    private val config: MessagePipelineConfig = MessagePipelineConfig(),
) {
    /** Runs the pipeline for [sms]; never throws except to pass on the caller's cancellation. */
    suspend fun receive(sms: IncomingSms): PipelineOutcome {
        val stored = store(sms)
        // The address and the extractors are regular expressions and libphonenumber only, so they share one short step.
        val extracted =
            bounded("extract", config.extractMillis) {
                resolver.addressOf(sms.address).let { address -> address to analyzer.hints(sms.body, address) }
            }
        val address = extracted?.first
        val hints = extracted?.second ?: TextHints()
        val sender =
            bounded("sender", config.senderMillis) { resolver.resolve(sms.address, address, sms.body, hints) }
                ?: ResolvedSender(address)
        return when (stored) {
            is AppResult.Success -> {
                classified(sms, stored.value, sender, hints)
            }

            is AppResult.Failure -> {
                Timber.e("Incoming SMS not stored: %s", stored.error)
                bounded("notify unsaved", config.notifyMillis) { notifier.unsaved(sms, sender.name) }
                PipelineOutcome.Unsaved(stored.error)
            }
        }
    }

    private suspend fun classified(
        sms: IncomingSms,
        message: StoredMessage,
        sender: ResolvedSender,
        hints: TextHints,
    ): PipelineOutcome {
        val analysis = bounded("analysis", config.analysisMillis) { analyzer.analyze(sms.body, sender, hints) } ?: MessageAnalysis.PERSONAL
        val guarded =
            if (analysis.category == MessageCategory.OTP) {
                bounded("otp guard", config.guardMillis) { otp.received("thread:${message.threadId}", sender.name) }.orEmpty()
            } else {
                emptySet()
            }
        val now = clock.nowMillis()
        val otpInfo = analysis.otp?.toOtpInfo(sender.address, now)
        val risks = analysis.risks + guarded
        val row =
            MessageMeta(
                providerId = message.key.id,
                threadId = message.threadId,
                category = analysis.category,
                senderName = sender.name,
                senderSource = sender.source,
                atMillis = now,
                otp = otpInfo,
                riskFlags = MessageRisk.toBits(risks),
                classifierScore = analysis.score,
            )
        bounded(
            "meta",
            config.metaMillis,
        ) { meta.save(row) }?.let { if (it is AppResult.Failure) Timber.w("Message meta not saved: %s", it.error) }
        val details = ReceivedDetails(sender.name, analysis.category, otpInfo, risks, analysis.imitates)
        bounded("notify", config.notifyMillis) { notifier.stored(message, details) }
        return PipelineOutcome.Stored(message, analysis.category)
    }

    // A refused write is tried once more (a busy provider can refuse one). A write that timed out isn't: it may still
    // land, and a second one would store the message twice.
    private suspend fun store(sms: IncomingSms): AppResult<StoredMessage> {
        val first = bounded("store", config.storeMillis / STORE_ATTEMPTS) { inbox.save(sms) }
        return when (first) {
            null -> AppResult.Failure(AppError.Storage)
            is AppResult.Success -> first
            is AppResult.Failure -> bounded("store again", config.storeMillis / STORE_ATTEMPTS) { inbox.save(sms) } ?: first
        }
    }

    // One step with its own time limit; a failure or timeout is logged and gives null.
    @Suppress("TooGenericExceptionCaught") // Each step's adapters throw different things; none may lose the message.
    private suspend fun <T> bounded(
        step: String,
        millis: Long,
        block: suspend () -> T,
    ): T? =
        try {
            withTimeoutOrNull(millis) { block() }.also {
                if (it ==
                    null
                ) {
                    Timber.w("Message pipeline step %s gave nothing or timed out", step)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Message pipeline step %s failed", step)
            null
        }

    private companion object {
        const val STORE_ATTEMPTS = 2
    }
}
