package io.github.jeshiomurmu.opencaller.core.data.backup

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.backupBytesToPreferences
import io.github.jeshiomurmu.opencaller.core.datastore.toBackupBytes
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Where a message is in the Telephony provider, and how to find it again on another phone. */
data class MessageRef(
    val providerId: Long,
    val date: Long,
    val address: String,
)

/**
 * Finds SMS in Android's Telephony provider (`core:telephony`). Needs OpenCaller to be the default SMS app; without
 * it, both calls find nothing and message categories aren't backed up or restored.
 */
interface MessageLocator {
    /** Date and address of the messages [providerIds] (missing ones are left out). */
    suspend fun describe(providerIds: List<Long>): List<MessageRef>

    /** For each of [refs], the provider id and thread of the message with the same date and address now, if any. */
    suspend fun locate(refs: List<MessageRef>): Map<Long, Pair<Long, Long>>
}

/** What a restore did. */
data class RestoreSummary(
    val rules: Int,
    val events: Int,
    val messages: Int,
    val messagesNotFound: Int,
)

/** Why a backup or restore failed. */
sealed interface BackupError {
    /** Creating a backup needs a supporter entitlement (restoring never does). */
    data object NotEntitled : BackupError

    /** The passphrase is too short. */
    data object PassphraseTooShort : BackupError

    /** Wrong passphrase, or the file was changed. */
    data object WrongPassphrase : BackupError

    /** Not an OpenCaller backup, or one from a newer version. */
    data object NotABackup : BackupError

    /** Reading or writing failed. */
    data class Failed(
        val error: AppError,
    ) : BackupError
}

/** The outcome of a backup action. */
sealed interface BackupResult<out T> {
    /** Done. */
    data class Done<T>(
        val value: T,
    ) : BackupResult<T>

    /** Not done. */
    data class Refused(
        val reason: BackupError,
    ) : BackupResult<Nothing>
}

/**
 * Backup and restore (docs/prd.md F-UT-05): settings, block rules, the screening log and message categories, as
 * one file encrypted with the user's passphrase ([BackupCipher]) for Google Drive (play) or a file the user picks
 * (both builds). Creating a backup needs an entitlement; restoring is always allowed, so nobody's data is locked
 * behind a lapsed subscription. A restore replaces the rules and the log and keeps this phone's onboarding state.
 */
@Singleton
class BackupRepository
    @Inject
    constructor(
        private val access: DatabaseAccess,
        private val settings: UserSettingsDataSource,
        private val entitlements: EntitlementProvider,
        private val messages: MessageLocator,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** The encrypted backup file, for [passphrase]. */
        suspend fun create(
            passphrase: CharArray,
            cipher: BackupCipher = BackupCipher(),
        ): BackupResult<ByteArray> =
            when {
                !entitlements.current().entitled -> BackupResult.Refused(BackupError.NotEntitled)
                passphrase.size < BackupCipher.MIN_PASSPHRASE_LENGTH -> BackupResult.Refused(BackupError.PassphraseTooShort)
                else -> contents().fold(passphrase, cipher)
            }

        /** Restores [file] with [passphrase]. */
        suspend fun restore(
            file: ByteArray,
            passphrase: CharArray,
            cipher: BackupCipher = BackupCipher(),
        ): BackupResult<RestoreSummary> =
            withContext(dispatchers.default) {
                try {
                    apply(BackupArchive.decode(cipher.decrypt(file, passphrase)))
                } catch (e: WrongPassphraseException) {
                    Timber.i(e, "Backup not decrypted")
                    BackupResult.Refused(BackupError.WrongPassphrase)
                } catch (e: BackupFormatException) {
                    Timber.w(e, "Not a readable backup")
                    BackupResult.Refused(BackupError.NotABackup)
                }
            }

        private suspend fun contents(): AppResult<BackupContents> {
            val read = storage.run { Triple(backupDao().rules(), backupDao().events(), backupDao().messageMeta()) }
            if (read !is AppResult.Success) return AppResult.Failure((read as AppResult.Failure).error)
            val (rules, events, meta) = read.value
            val refs = messages.describe(meta.map { it.providerId }).associateBy { it.providerId }
            return AppResult.Success(
                BackupContents(
                    createdAtMillis = clock.nowMillis(),
                    settings = Base64.getEncoder().encodeToString(settings.preferences.first().toBackupBytes()),
                    rules = rules.map { it.toBackup() },
                    events = events.map { it.toBackup() },
                    messages = meta.map { it.toBackup(refs[it.providerId]) },
                ),
            )
        }

        private suspend fun AppResult<BackupContents>.fold(
            passphrase: CharArray,
            cipher: BackupCipher,
        ): BackupResult<ByteArray> =
            when (this) {
                is AppResult.Failure -> {
                    BackupResult.Refused(BackupError.Failed(error))
                }

                is AppResult.Success -> {
                    withContext(
                        dispatchers.default,
                    ) { BackupResult.Done(cipher.encrypt(BackupArchive.encode(value), passphrase)) }
                }
            }

        private suspend fun apply(contents: BackupContents): BackupResult<RestoreSummary> {
            val prefs =
                try {
                    backupBytesToPreferences(Base64.getDecoder().decode(contents.settings), settings.preferences.first().onboardingStep)
                } catch (e: IllegalArgumentException) {
                    unreadable("settings are unreadable", e)
                }
            val rules = contents.rules.mapNotNull { it.toEntity() }
            val events = contents.events.mapNotNull { it.toEntity() }
            val located =
                messages.locate(
                    contents.messages.mapNotNull { m ->
                        m.date?.let { d -> m.address?.let { MessageRef(m.providerId, d, it) } }
                    },
                )
            val meta = contents.messages.mapNotNull { m -> located[m.providerId]?.let { (id, thread) -> m.toEntity(id, thread) } }
            val written = storage.run { backupDao().restore(rules, events, meta) }
            if (written is AppResult.Failure) return BackupResult.Refused(BackupError.Failed(written.error))
            settings.update { prefs }
            return BackupResult.Done(RestoreSummary(rules.size, events.size, meta.size, contents.messages.size - meta.size))
        }
    }

private fun BlockRuleEntity.toBackup() = BackupRule(type.name, pattern, action.name, appliesTo.name, createdAt, enabled)

private fun ScreeningEventEntity.toBackup() = BackupEvent(e164, direction.name, decision.name, reason, spamScore, stirStatus, latencyMs, at)

private fun MessageMetaEntity.toBackup(ref: MessageRef?) =
    BackupMessage(providerId, ref?.date, ref?.address, category.name, senderName, senderSource?.name, riskFlags, classifierScore, at)

// Rows with values this version doesn't know (from a newer app) are skipped, never guessed.
private fun BackupRule.toEntity(): BlockRuleEntity? {
    val ruleType = enumOrNull<RuleType>(type)
    val ruleAction = enumOrNull<RuleAction>(action)
    val target = enumOrNull<RuleTarget>(appliesTo)
    return if (ruleType == null || ruleAction == null || target == null) {
        null
    } else {
        BlockRuleEntity(
            type = ruleType,
            pattern = pattern,
            action = ruleAction,
            appliesTo = target,
            createdAt = createdAt,
            enabled = enabled,
        )
    }
}

private fun BackupEvent.toEntity(): ScreeningEventEntity? {
    val dir = enumOrNull<Direction>(direction)
    val dec = enumOrNull<Decision>(decision)
    return if (dir == null || dec == null) {
        null
    } else {
        ScreeningEventEntity(
            e164 = e164,
            direction = dir,
            decision = dec,
            reason = reason,
            spamScore = spamScore,
            stirStatus = stirStatus,
            latencyMs = latencyMs,
            at = at,
        )
    }
}

private fun BackupMessage.toEntity(
    providerId: Long,
    threadId: Long,
): MessageMetaEntity? {
    val cat = enumOrNull<MessageCategory>(category) ?: return null
    return MessageMetaEntity(
        providerId = providerId,
        threadId = threadId,
        category = cat,
        senderName = senderName,
        senderSource = senderSource?.let { enumOrNull<NameSource>(it) },
        otpCode = null,
        otpOrigin = null,
        otpExpiresAt = null,
        riskFlags = riskFlags,
        classifierScore = classifierScore,
        at = at,
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
