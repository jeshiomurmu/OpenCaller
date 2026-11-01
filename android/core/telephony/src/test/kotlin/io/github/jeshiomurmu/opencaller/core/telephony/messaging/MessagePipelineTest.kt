package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.otp.CallActivityTracker
import io.github.jeshiomurmu.opencaller.core.telephony.otp.CallParty
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingNotifier : IncomingMessageNotifier {
    val stored = mutableListOf<Pair<StoredMessage, ReceivedDetails>>()
    val unsaved = mutableListOf<Pair<IncomingSms, String?>>()

    override suspend fun stored(
        message: StoredMessage,
        details: ReceivedDetails,
    ) {
        stored += message to details
    }

    override suspend fun unsaved(
        sms: IncomingSms,
        senderName: String?,
    ) {
        unsaved += sms to senderName
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessagePipelineTest {
    private val sms = IncomingSms("+15550001111", "Hello there", sentAtMillis = 1_000, subscriptionId = 2)
    private val where = StoredMessage(MessageKey(MessageKind.SMS, 41), threadId = 7)
    private val clock = FakeClock(5_000)
    private val notifier = RecordingNotifier()
    private val metas = mutableListOf<MessageMeta>()
    private val calls = CallActivityTracker(clock)
    private val alarm = RecordingAlarm()

    private fun pipeline(
        inbox: SmsInbox = SmsInbox { AppResult.Success(where) },
        senders: SenderNames = SenderNames { "Ana" },
        meta: MessageMetaSink =
            MessageMetaSink {
                metas += it
                AppResult.Success(Unit)
            },
    ) = MessagePipeline(inbox, senderResolver(senders), messageAnalyzer(), otpWatch(calls, alarm, clock), meta, notifier, clock)

    @Test
    fun tcSms001AStoredMessageGetsItsMetaAndANotification() =
        runTest {
            val outcome = pipeline().receive(sms)

            assertEquals(PipelineOutcome.Stored(where), outcome)
            val meta = metas.single()
            assertEquals(41L, meta.providerId)
            assertEquals(7L, meta.threadId)
            assertEquals(MessageCategory.PERSONAL, meta.category)
            assertEquals("Ana", meta.senderName)
            assertEquals(NameSource.CONTACT, meta.senderSource)
            assertEquals(5_000L, meta.atMillis)
            assertTrue("the text is never part of the meta row", "Hello" !in meta.toString() && metas.none { it.otp != null })
            assertEquals(listOf(where to ReceivedDetails("Ana")), notifier.stored)
        }

    @Test
    fun aOneTimeCodeIsStoredWithItsCodeAndExpiryAndShownAsOne() =
        runTest {
            val code = IncomingSms("JD-SBIOTP-S", "Your OTP is 482913. Valid for 5 minutes.", sentAtMillis = 1_000)
            val outcome = pipeline(senders = { null }).receive(code)

            assertEquals(PipelineOutcome.Stored(where, MessageCategory.OTP), outcome)
            val meta = metas.single()
            assertEquals(MessageCategory.OTP, meta.category)
            assertEquals("482913", meta.otp?.code)
            assertEquals(5_000L + 5 * 60_000L, meta.otp?.expiresAtMillis)
            assertEquals(0, meta.riskFlags)
            assertEquals(
                MessageCategory.OTP,
                notifier.stored
                    .single()
                    .second.category,
            )
            assertEquals(
                "482913",
                notifier.stored
                    .single()
                    .second.otp
                    ?.code,
            )
            assertTrue(alarm.duringCall.isEmpty())
        }

    @Test
    fun tcOtp005ACodeDuringACallWithAStrangerRaisesTheGuard() =
        runTest {
            calls.started("c1", PhoneNumber("+15550109999"), CallParty.STRANGER, incoming = true)
            calls.answered("c1")
            pipeline(senders = { null }).receive(IncomingSms("Ridely", "Your Ridely code is 551234.", sentAtMillis = 1_000))

            assertEquals(listOf<Pair<String?, Boolean>>(null to false), alarm.duringCall)
            assertEquals(setOf(MessageRisk.OTP_DURING_CALL), MessageRisk.fromBits(metas.single().riskFlags))
            assertTrue(
                MessageRisk.OTP_DURING_CALL in
                    notifier.stored
                        .single()
                        .second.risks,
            )
        }

    @Test
    fun aSenderWhoIsNoContactHasNoName() =
        runTest {
            pipeline(senders = { null }).receive(sms)
            assertNull(metas.single().senderName)
            assertNull(metas.single().senderSource)
        }

    @Test
    fun aRefusedWriteIsTriedOnceMore() =
        runTest {
            var attempts = 0
            val outcome =
                pipeline(inbox = {
                    if (++attempts ==
                        1
                    ) {
                        AppResult.Failure(AppError.Storage)
                    } else {
                        AppResult.Success(where)
                    }
                }).receive(sms)
            assertEquals(2, attempts)
            assertEquals(PipelineOutcome.Stored(where), outcome)
        }

    @Test
    fun aMessageTheStoreRefusesIsStillShown() =
        runTest {
            val outcome = pipeline(inbox = { AppResult.Failure(AppError.PermissionDenied) }).receive(sms)
            assertEquals(PipelineOutcome.Unsaved(AppError.PermissionDenied), outcome)
            assertEquals(listOf(sms to "Ana"), notifier.unsaved)
            assertTrue(metas.isEmpty())
        }

    @Test
    fun aSlowWriteIsNotRepeated() =
        runTest {
            var attempts = 0
            val outcome =
                pipeline(inbox = {
                    attempts++
                    awaitCancellation()
                }).receive(sms)
            assertEquals("a write that may still land is not repeated", 1, attempts)
            assertEquals(PipelineOutcome.Unsaved(AppError.Storage), outcome)
        }

    @Test
    fun failingLookupsLeaveTheMessagePersonalAndInTime() =
        runTest {
            val outcome =
                pipeline(
                    senders = {
                        delay(60_000)
                        "late"
                    },
                    meta = { error("database gone") },
                ).receive(sms)
            assertEquals(PipelineOutcome.Stored(where), outcome)
            assertEquals(listOf(where to ReceivedDetails(null)), notifier.stored)
            assertTrue("within the broadcast's 10 s", currentTime <= MessagePipelineConfig().totalMillis)
            assertTrue(MessagePipelineConfig().totalMillis < 10_000)
        }

    @Test
    fun deliveryReportsAreRead() {
        assertEquals(DeliveryState.DELIVERED, DeliveryReports.stateOf(0x00, "3gpp"))
        assertEquals(DeliveryState.DELIVERED, DeliveryReports.stateOf(0x02, "3gpp"))
        assertEquals(DeliveryState.PENDING, DeliveryReports.stateOf(0x20, "3gpp"))
        assertEquals(DeliveryState.FAILED, DeliveryReports.stateOf(0x41, "3gpp"))
        assertEquals(DeliveryState.FAILED, DeliveryReports.stateOf(0x60, null))
        assertEquals(DeliveryState.DELIVERED, DeliveryReports.stateOf(0x0002_0000, "3gpp2"))
        assertEquals(DeliveryState.PENDING, DeliveryReports.stateOf(0x0200_0000, "3gpp2"))
        assertEquals(DeliveryState.FAILED, DeliveryReports.stateOf(0x0300_0000, "3gpp2"))
    }

    @Test
    fun tcSms002AMultipartMessageIsDeliveredWithItsLastPart() {
        assertNull("parts before the last don't decide", DeliveryReports.combine(DeliveryState.DELIVERED, 0, 3))
        assertEquals(DeliveryState.DELIVERED, DeliveryReports.combine(DeliveryState.DELIVERED, 2, 3))
        assertEquals(DeliveryState.FAILED, DeliveryReports.combine(DeliveryState.FAILED, 0, 3))
        assertNull(DeliveryReports.combine(DeliveryState.PENDING, 2, 3))
    }
}
