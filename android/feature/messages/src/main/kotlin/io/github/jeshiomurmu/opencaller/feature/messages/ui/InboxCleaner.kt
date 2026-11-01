package io.github.jeshiomurmu.opencaller.feature.messages.ui

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.MessageMetaRepository
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The inbox cleaner (PRD F-SMS-03): one-time codes older than a day, and deliveries, promotions, spam and phishing
 * older than 30 days. Personal messages, transactions and bills are never deleted by it. Messages go from Android's
 * message store and their OpenCaller metadata with them.
 */
@Singleton
class InboxCleaner
    @Inject
    constructor(
        private val meta: MessageMetaRepository,
        private val conversations: ConversationSource,
        private val settings: UserSettingsDataSource,
        private val clock: Clock,
    ) : InboxCleanup {
        override suspend fun plan(): AppResult<CleanPlan> {
            val now = clock.nowMillis()
            return meta.olderThan(listOf(MessageCategory.OTP), now - OTP_AGE_MILLIS).flatMap { codes ->
                meta.olderThan(LONG_LIVED, now - OLD_AGE_MILLIS).map { old ->
                    val rows = codes + old
                    CleanPlan(rows.groupingBy { it.category }.eachCount(), rows.map { it.providerId })
                }
            }
        }

        override suspend fun clean(plan: CleanPlan): AppResult<Int> =
            if (plan.ids.isEmpty()) {
                AppResult.Success(0)
            } else {
                conversations.deleteSms(plan.ids).also { deleted ->
                    if (deleted is AppResult.Success) plan.ids.forEach { meta.delete(it) }
                }
            }

        override fun autoDelete(): Flow<Boolean> = settings.preferences.map { it.otpAutoDelete }.distinctUntilChanged()

        override suspend fun setAutoDelete(enabled: Boolean) = settings.update { it.copy(otpAutoDelete = enabled) }

        /** Deletes codes older than a day when auto-delete is on (run by `OtpCleanupWorker`). */
        suspend fun deleteOldCodes(): AppResult<Int> =
            meta.olderThan(listOf(MessageCategory.OTP), clock.nowMillis() - OTP_AGE_MILLIS).flatMap { codes ->
                clean(CleanPlan(mapOf(MessageCategory.OTP to codes.size), codes.map { it.providerId }))
            }

        private companion object {
            const val OTP_AGE_MILLIS = 24 * 60 * 60 * 1000L
            const val OLD_AGE_MILLIS = 30 * OTP_AGE_MILLIS
            val LONG_LIVED = listOf(MessageCategory.DELIVERY, MessageCategory.PROMOTION, MessageCategory.SPAM, MessageCategory.PHISHING)
        }
    }
