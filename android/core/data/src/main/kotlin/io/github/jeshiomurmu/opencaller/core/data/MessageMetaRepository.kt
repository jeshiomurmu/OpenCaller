package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.OtpInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What OpenCaller knows about received SMS beyond Android's message store: category, sender name, code. The text
 * itself is never stored here (docs/architecture.md §10); the row points at the message by its provider id.
 */
@Singleton
class MessageMetaRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Saves or replaces [meta]. */
        suspend fun save(meta: MessageMeta): AppResult<Unit> = storage.run { messageMetaDao().upsert(meta.toEntity()) }

        /** The metadata of SMS [providerId], or `null`. */
        suspend fun find(providerId: Long): AppResult<MessageMeta?> =
            storage.run { messageMetaDao().find(providerId) }.map { it?.toDomain() }

        /** Forgets the metadata of the messages in [threadId] (the conversation was deleted). */
        suspend fun deleteThread(threadId: Long): AppResult<Int> = storage.run { messageMetaDao().deleteThread(threadId) }
    }

internal fun MessageMeta.toEntity(): MessageMetaEntity =
    MessageMetaEntity(
        providerId = providerId,
        threadId = threadId,
        category = category,
        senderName = senderName,
        senderSource = senderSource,
        otpCode = otp?.code,
        otpOrigin = otp?.origin,
        otpExpiresAt = otp?.expiresAtMillis,
        riskFlags = riskFlags,
        classifierScore = classifierScore,
        at = atMillis,
    )

internal fun MessageMetaEntity.toDomain(): MessageMeta =
    MessageMeta(
        providerId = providerId,
        threadId = threadId,
        category = category,
        senderName = senderName,
        senderSource = senderSource,
        atMillis = at,
        otp = otpCode?.takeIf(String::isNotBlank)?.let { OtpInfo(it, origin = otpOrigin, expiresAtMillis = otpExpiresAt) },
        riskFlags = riskFlags,
        classifierScore = classifierScore,
    )
