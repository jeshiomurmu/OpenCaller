package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceSmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends SMS (TC-SMS-002): the message goes into the outbox first, is split with `SmsManager.divideMessage` and sent
 * with `sendMultipartTextMessage` from the chosen SIM, with a sent and a delivery PendingIntent per part
 * ([MessageStatusReceiver] moves it to sent or failed and records the delivery report).
 */
@Singleton
class SmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: DeviceSmsStore,
    ) {
        /** Sends [body] to [address] with SIM [subscriptionId] (`null`: the default SMS SIM). */
        suspend fun send(
            address: String,
            body: String,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            when {
                address.isBlank() || body.isBlank() -> {
                    AppResult.Failure(AppError.Validation("empty message"))
                }

                !canSend() -> {
                    AppResult.Failure(AppError.PermissionDenied)
                }

                else -> {
                    store.addOutgoing(address.trim(), body, subscriptionId, deliveryReport = true).flatMap {
                        dispatch(it, address.trim(), body, subscriptionId)
                    }
                }
            }

        /** Sends failed message [id] again. */
        suspend fun resend(id: Long): AppResult<StoredMessage> {
            if (!canSend()) return AppResult.Failure(AppError.PermissionDenied)
            val message =
                store.outgoing(id).flatMap { found ->
                    found?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.NotFound)
                }
            return message.flatMap { sms ->
                store.requeue(id).flatMap {
                    dispatch(StoredMessage(MessageKey(MessageKind.SMS, id), UNKNOWN_THREAD), sms.address, sms.body, sms.subscriptionId)
                }
            }
        }

        private fun canSend(): Boolean = context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        // canSend() checked SEND_SMS just before; a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        private suspend fun dispatch(
            stored: StoredMessage,
            address: String,
            body: String,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> {
            val id = stored.key.id
            val manager = context.smsManagerFor(subscriptionId) ?: return failed(id, "no SMS on this device")
            return try {
                val parts = manager.divideMessage(body)
                val sent = ArrayList(parts.indices.map { MessagingIntents.smsSent(context, stored, it, parts.size) })
                val delivered = ArrayList(parts.indices.map { MessagingIntents.smsDelivered(context, stored, it, parts.size) })
                manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
                Timber.i("SMS %d handed to the network in %d parts", id, parts.size)
                AppResult.Success(stored)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "SMS refused")
                failed(id, "refused")
            } catch (e: SecurityException) {
                Timber.w(e, "SMS not allowed")
                failed(id, "not allowed")
            }
        }

        private suspend fun failed(
            id: Long,
            why: String,
        ): AppResult<StoredMessage> {
            store.markFailed(id, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            return AppResult.Failure(AppError.Validation(why))
        }

        private companion object {
            // Resending doesn't need the thread; the result receiver reads the message by its id.
            const val UNKNOWN_THREAD = -1L
        }
    }
