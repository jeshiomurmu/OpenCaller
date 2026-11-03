package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupArchive
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupCipher
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupError
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupRepository
import io.github.jeshiomurmu.opencaller.core.data.backup.BackupResult
import io.github.jeshiomurmu.opencaller.core.data.backup.MessageLocator
import io.github.jeshiomurmu.opencaller.core.data.backup.MessageRef
import io.github.jeshiomurmu.opencaller.core.data.backup.RestoreSummary
import io.github.jeshiomurmu.opencaller.core.data.supporter.StaticEntitlements
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TC-UT-003: rules and settings come back identically on a fresh install; the passphrase protects the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class BackupTest {
    private val cipher = BackupCipher(iterations = 1_000)
    private val supporter =
        StaticEntitlements(
            Entitlement(
                true,
                PaidTier.SUPPORTER,
                EntitlementSource.BILLING,
                expiresAtMillis =
                    Long.MAX_VALUE / 2,
            ),
        )
    private val oldPhone = inMemoryDatabase()
    private val newPhone = inMemoryDatabase()
    private val passphrase = "correct horse battery".toCharArray()

    private val preferences =
        UserPreferences(
            blockHiddenNumbers = true,
            blockForeignNumbers = true,
            spamThreshold = SpamThreshold.HIGH,
            allowedRegionCodes = setOf("BD", "IN"),
            announceCallerMode = AnnounceCallerMode.HEADSET_ONLY,
            themeMode = ThemeMode.DARK,
            onboardingStep = OnboardingStep.DONE,
            quickReplies = listOf("Can't talk now", "Call you later"),
            goldTheme = true,
            ghostCall = GhostCallSetup("Mum", "+44 7700 900123", delaySeconds = 60),
        )

    // The same SMS sits under another provider id on the new phone; one message isn't there at all.
    private val locator =
        object : MessageLocator {
            override suspend fun describe(providerIds: List<Long>): List<MessageRef> =
                providerIds.filter { it != GONE }.map { MessageRef(it, 1_700_000_000_000L + it, "+8801712345678") } +
                    providerIds.filter { it == GONE }.map { MessageRef(it, 1, "+8801700000000") }

            override suspend fun locate(refs: List<MessageRef>): Map<Long, Pair<Long, Long>> =
                refs.filter { it.providerId != GONE }.associate { it.providerId to ((it.providerId + 100) to 9L) }
        }

    @After
    fun tearDown() {
        oldPhone.close()
        newPhone.close()
    }

    private fun repository(
        db: io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase,
        settings: UserSettingsDataSource,
        entitlements: StaticEntitlements = supporter,
    ) = BackupRepository(DatabaseAccess { db }, settings, entitlements, locator, FakeClock(), RealDispatchers)

    private suspend fun seed() {
        oldPhone.blockRuleDao().insert(
            BlockRuleEntity(
                type = RuleType.PREFIX,
                pattern = "+1900",
                action = RuleAction.BLOCK,
                appliesTo = RuleTarget.CALLS,
                createdAt = 5,
                enabled = true,
            ),
        )
        oldPhone.blockRuleDao().insert(
            BlockRuleEntity(
                type = RuleType.EXACT,
                pattern = "+12015550123",
                action = RuleAction.ALLOW,
                appliesTo = RuleTarget.BOTH,
                createdAt = 6,
                enabled = false,
            ),
        )
        oldPhone.screeningEventDao().insert(
            ScreeningEventEntity(
                e164 = "+12015550199",
                direction = Direction.INCOMING,
                decision = Decision.BLOCK,
                reason = "spam_list",
                spamScore = 0.9f,
                stirStatus = null,
                latencyMs = 12,
                at = 1_000,
            ),
        )
        oldPhone.messageMetaDao().upsert(meta(providerId = 7, otp = "482913"))
        oldPhone.messageMetaDao().upsert(meta(providerId = GONE, otp = null))
    }

    private fun meta(
        providerId: Long,
        otp: String?,
    ) = MessageMetaEntity(
        providerId,
        threadId = 3,
        category = MessageCategory.OTP,
        senderName = "Example Bank",
        senderSource = NameSource.SENDER_REGISTRY,
        otpCode = otp,
        otpOrigin = "sms",
        otpExpiresAt = 99,
        riskFlags = 2,
        classifierScore = 0.8f,
        at = 42,
    )

    @Test
    fun rulesSettingsAndHistoryComeBackIdenticallyOnAFreshInstall() =
        runBlocking {
            seed()
            val oldSettings = UserSettingsDataSource.inMemory(preferences)
            val file = (repository(oldPhone, oldSettings).create(passphrase, cipher) as BackupResult.Done).value

            // A fresh install: default settings, still onboarding.
            val newSettings = UserSettingsDataSource.inMemory(UserPreferences(onboardingStep = OnboardingStep.CONTACTS))
            val restored = repository(newPhone, newSettings, StaticEntitlements()).restore(file, passphrase, cipher)
            assertEquals(BackupResult.Done(RestoreSummary(rules = 2, events = 1, messages = 1, messagesNotFound = 1)), restored)

            fun BlockRuleEntity.content() = listOf(type, pattern, action, appliesTo, createdAt, enabled)
            assertEquals(oldPhone.backupDao().rules().map { it.content() }, newPhone.backupDao().rules().map { it.content() })
            assertEquals(oldPhone.backupDao().events().map { it.copy(id = 0) }, newPhone.backupDao().events().map { it.copy(id = 0) })
            // Settings are identical except this phone's own onboarding state.
            assertEquals(preferences.copy(onboardingStep = OnboardingStep.CONTACTS), newSettings.preferences.first())
            // The message was found under its new id; its one-time code was never in the backup.
            val moved = newPhone.messageMetaDao().find(107)!!
            assertEquals(meta(providerId = 107, otp = null).copy(threadId = 9, otpOrigin = null, otpExpiresAt = null), moved)
        }

    @Test
    fun theFileIsEncryptedAndNeedsItsPassphrase() =
        runBlocking {
            seed()
            val settings = UserSettingsDataSource.inMemory(preferences)
            val file = (repository(oldPhone, settings).create(passphrase, cipher) as BackupResult.Done).value
            val text = file.toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("OCBK"))
            assertFalse("+1900" in text || "Example Bank" in text || "482913" in text)

            val target = repository(newPhone, UserSettingsDataSource.inMemory())
            assertEquals(BackupResult.Refused(BackupError.WrongPassphrase), target.restore(file, "wrong passphrase".toCharArray(), cipher))
            file[file.size - 1] = (file[file.size - 1].toInt() xor 1).toByte()
            assertEquals(BackupResult.Refused(BackupError.WrongPassphrase), target.restore(file, passphrase, cipher))
            assertEquals(BackupResult.Refused(BackupError.NotABackup), target.restore("hello".toByteArray(), passphrase, cipher))
            assertTrue(newPhone.backupDao().rules().isEmpty())
        }

    @Test
    fun makingABackupNeedsAnEntitlementAndALongEnoughPassphrase() =
        runBlocking {
            val settings = UserSettingsDataSource.inMemory(preferences)
            assertEquals(
                BackupResult.Refused(BackupError.NotEntitled),
                repository(oldPhone, settings, StaticEntitlements()).create(passphrase, cipher),
            )
            assertEquals(
                BackupResult.Refused(BackupError.PassphraseTooShort),
                repository(oldPhone, settings).create("short".toCharArray(), cipher),
            )
        }

    @Test
    fun theArchiveLeavesOutOneTimeCodes() =
        runBlocking {
            seed()
            val settings = UserSettingsDataSource.inMemory(preferences)
            val file = (repository(oldPhone, settings).create(passphrase, cipher) as BackupResult.Done).value
            val contents = BackupArchive.decode(cipher.decrypt(file, passphrase))
            assertEquals(2, contents.messages.size)
            assertFalse(BackupArchive.encode(contents).toString(Charsets.ISO_8859_1).contains("482913"))
        }

    private companion object {
        const val GONE = 8L
    }
}
