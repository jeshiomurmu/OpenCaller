package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/** What the assistant agent tells the app during a call (ADR 0010; `backend/agents` `assistant_protocol.py`). */
sealed interface AssistantEvent {
    /** The call started: the caller's number, or `null` when the network withheld it. */
    data class Hello(
        val caller: PhoneNumber?,
    ) : AssistantEvent

    /** What the caller is saying right now (not final). */
    data class Partial(
        val text: String,
    ) : AssistantEvent

    /** A finished line of the conversation. */
    data class Line(
        val line: TranscriptLine,
    ) : AssistantEvent

    /** The assistant's summary of the caller. */
    data class Details(
        val details: CallerDetails,
    ) : AssistantEvent

    /** The call ended. */
    data class Ended(
        val end: ScreeningEnd,
    ) : AssistantEvent
}

/** What the user asks the assistant to do. */
sealed interface AssistantAction {
    /** Say [text] to the caller. */
    data class Reply(
        val text: String,
    ) : AssistantAction

    /** Tell the caller the user will call back, and hang up. */
    data object CallBack : AssistantAction

    /** End the call: it's spam. */
    data object Spam : AssistantAction
}

/**
 * The data channel's JSON messages (topic [TOPIC], version 1). Unknown or malformed messages are dropped, never
 * fatal: the agent and the app may be different versions.
 */
object AssistantMessages {
    /** The LiveKit data topic. */
    const val TOPIC = "opencaller.assistant"

    /** The longest reply the agent accepts. */
    const val MAX_REPLY_CHARS = 200

    private const val MAX_BYTES = 8192
    private val json = Json { ignoreUnknownKeys = true }

    /** The event in [payload], or `null`. */
    fun parse(payload: ByteArray): AssistantEvent? = payload.takeIf { it.size <= MAX_BYTES }?.let(::objectOf)?.let(::eventOf)

    // An unreadable message is dropped (logged without its content, which may hold a phone number).
    private fun objectOf(payload: ByteArray): JsonObject? =
        try {
            json.parseToJsonElement(payload.decodeToString()).jsonObject
        } catch (e: SerializationException) {
            Timber.i(e, "Unreadable assistant message")
            null
        } catch (e: IllegalArgumentException) {
            Timber.i(e, "Assistant message isn't an object")
            null
        }

    private fun eventOf(message: JsonObject): AssistantEvent? =
        when (message.text("t")) {
            "hello" -> AssistantEvent.Hello(message.text("caller")?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber))
            "partial" -> message.text("text")?.let(AssistantEvent::Partial)
            "line" -> line(message)
            "details" -> AssistantEvent.Details(details(message))
            "ended" -> AssistantEvent.Ended(endOf(message.text("reason")))
            else -> null
        }

    /** [action] as the agent reads it. */
    fun encode(action: AssistantAction): ByteArray =
        when (action) {
            is AssistantAction.Reply -> {
                buildJsonObject {
                    put("t", "reply")
                    put("text", action.text.trim().take(MAX_REPLY_CHARS))
                }
            }

            AssistantAction.CallBack -> {
                buildJsonObject { put("t", "callback") }
            }

            AssistantAction.Spam -> {
                buildJsonObject { put("t", "spam") }
            }
        }.toString().encodeToByteArray()

    /** How the agent names [end]. */
    internal fun endOf(reason: String?): ScreeningEnd =
        when (reason) {
            "caller_hung_up" -> ScreeningEnd.CALLER_HUNG_UP
            "callback" -> ScreeningEnd.CALL_BACK
            "spam" -> ScreeningEnd.SPAM
            "done" -> ScreeningEnd.DONE
            "time" -> ScreeningEnd.TIME_UP
            else -> ScreeningEnd.UNKNOWN
        }

    private fun line(message: JsonObject): AssistantEvent? {
        val speaker =
            when (message.text("role")) {
                "caller" -> Speaker.CALLER
                "assistant" -> Speaker.ASSISTANT
                else -> null
            }
        val text = message.text("text")
        return if (speaker != null && text != null) AssistantEvent.Line(TranscriptLine(speaker, text)) else null
    }

    private fun details(message: JsonObject) =
        CallerDetails(
            name = message.text("name"),
            reason = message.text("reason"),
            urgency = CallUrgency.entries.firstOrNull { it.name.equals(message.text("urgency"), ignoreCase = true) } ?: CallUrgency.NORMAL,
            likelySpam = (message["likelySpam"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf {
                it.isString
            }?.contentOrNull
            ?.takeIf { it.isNotBlank() }
}
