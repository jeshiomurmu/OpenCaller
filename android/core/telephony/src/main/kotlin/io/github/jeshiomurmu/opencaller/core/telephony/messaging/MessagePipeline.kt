package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.NameSource
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

/** Tells the user about received messages. */
interface IncomingMessageNotifier {
    /** A message was stored in thread [message]; shows (or updates) that conversation's notification. */
    suspend fun stored(
        message: StoredMessage,
        senderName: String?,
    )

    /** Android's message store refused [sms]; shows the text itself so it isn't lost silently. */
    suspend fun unsaved(
        sms: IncomingSms,
        senderName: String?,
    )
}

/** Time limits of [MessagePipeline]; together they stay under the 10 s a broadcast receiver may take. */
data class MessagePipelineConfig(
    val storeMillis: Long = 5_000,
    val senderMillis: Long = 1_000,
    val metaMillis: Long = 1_000,
    val notifyMillis: Long = 1_500,
) {
    /** The longest the pipeline can take. */
    val totalMillis: Long get() = storeMillis + senderMillis + metaMillis + notifyMillis
}

/** What became of a received SMS. */
sealed interface PipelineOutcome {
    /** Stored in Android's message store at [message]. */
    data class Stored(
        val message: StoredMessage,
    ) : PipelineOutcome

    /** Android's message store refused it with [error]; the user was shown the text in a notification. */
    data class Unsaved(
        val error: AppError,
    ) : PipelineOutcome
}

/**
 * The incoming-SMS pipeline of docs/architecture.md §4.4. Phase 7a runs step 1 (store the message: as the default SMS
 * app OpenCaller must) and step 7 (a [MessageMeta] row with the category and the sender's contact name, then the
 * notification). Every message is Personal until the classifier of Phase 7b; any failure after storing leaves it
 * Personal too (docs/rules.md §5). The text is never written to OpenCaller's database.
 */
class MessagePipeline(
    private val inbox: SmsInbox,
    private val senders: SenderNames,
    private val meta: MessageMetaSink,
    private val notifier: IncomingMessageNotifier,
    private val clock: Clock,
    private val config: MessagePipelineConfig = MessagePipelineConfig(),
) {
    /** Runs the pipeline for [sms]; never throws except to pass on the caller's cancellation. */
    suspend fun receive(sms: IncomingSms): PipelineOutcome {
        val stored = store(sms)
        val name = senderName(sms.address)
        if (stored !is AppResult.Success) {
            val error = (stored as AppResult.Failure).error
            Timber.e("Incoming SMS not stored: %s", error)
            bounded("notify unsaved", config.notifyMillis) { notifier.unsaved(sms, name) }
            return PipelineOutcome.Unsaved(error)
        }
        val message = stored.value
        val row =
            MessageMeta(
                providerId = message.key.id,
                threadId = message.threadId,
                category = MessageCategory.PERSONAL,
                senderName = name,
                senderSource = name?.let { NameSource.CONTACT },
                atMillis = clock.nowMillis(),
            )
        bounded(
            "meta",
            config.metaMillis,
        ) { meta.save(row) }?.let { if (it is AppResult.Failure) Timber.w("Message meta not saved: %s", it.error) }
        bounded("notify", config.notifyMillis) { notifier.stored(message, name) }
        return PipelineOutcome.Stored(message)
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

    private suspend fun senderName(address: String): String? =
        bounded("sender", config.senderMillis) {
            senders.nameOf(address)
        }?.takeIf(String::isNotBlank)

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
