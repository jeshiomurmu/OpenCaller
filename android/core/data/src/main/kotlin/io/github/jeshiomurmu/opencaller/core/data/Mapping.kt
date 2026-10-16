package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason

// Stored STIR/SHAKEN codes are android.telecom.Connection.VERIFICATION_STATUS_* values; null means unknown.
private const val STIR_NOT_VERIFIED = 0
private const val STIR_PASSED = 1
private const val STIR_FAILED = 2

internal fun BlockRuleEntity.toDomain(): BlockRule =
    BlockRule(
        id = id,
        type = type,
        pattern = pattern,
        action = action,
        target = appliesTo,
        createdAtMillis = createdAt,
        enabled = enabled,
    )

internal fun CallerVerification.toStored(): Int? =
    when (this) {
        CallerVerification.UNKNOWN -> null
        CallerVerification.NOT_VERIFIED -> STIR_NOT_VERIFIED
        CallerVerification.PASSED -> STIR_PASSED
        CallerVerification.FAILED -> STIR_FAILED
    }

internal fun Int?.toVerification(): CallerVerification =
    when (this) {
        STIR_NOT_VERIFIED -> CallerVerification.NOT_VERIFIED
        STIR_PASSED -> CallerVerification.PASSED
        STIR_FAILED -> CallerVerification.FAILED
        else -> CallerVerification.UNKNOWN
    }

internal fun ScreeningEvent.toEntity(): ScreeningEventEntity =
    ScreeningEventEntity(
        id = id,
        e164 = number?.e164,
        direction = direction,
        decision = decision,
        reason = reason.code,
        spamScore = spamScore,
        stirStatus = verification.toStored(),
        latencyMs = latencyMs,
        at = atMillis,
    )

/** Rows with a reason code this version doesn't know (written by a newer version) read as [ScreeningReason.NO_MATCH]. */
internal fun ScreeningEventEntity.toDomain(): ScreeningEvent =
    ScreeningEvent(
        id = id,
        number = e164?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber),
        direction = direction,
        decision = decision,
        reason = ScreeningReason.fromCode(reason) ?: ScreeningReason.NO_MATCH,
        spamScore = spamScore,
        verification = stirStatus.toVerification(),
        latencyMs = latencyMs,
        atMillis = at,
    )
