package io.github.jeshiomurmu.opencaller.feature.blocking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ReportRepository
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.data.SpamListRepository
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.DeviceSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ListSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.NoNetworkLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidator
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import io.github.jeshiomurmu.opencaller.core.telephony.screening.TimeLimitedRegex
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/**
 * Real repositories over an unencrypted in-memory database (direct executors, so writes are visible at once) and
 * the real screening engine with an empty contact list, for view model tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestGraph {
    val db: OpenCallerDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenCallerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    val dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher())
    val clock = FakeClock()
    val normalizer = PhoneNumberNormalizer()
    val settings = UserSettingsDataSource.inMemory()
    val contacts = mutableMapOf<PhoneNumber, String>()
    var canReadContacts = true
    private val access = DatabaseAccess { db }
    val rules = BlockRuleRepository(access, dispatchers, clock)
    val events = ScreeningEventRepository(access, dispatchers, clock)
    val spam = SpamListRepository(access, dispatchers)
    val reports =
        ReportRepository(
            access,
            dispatchers,
            clock,
            object : BackgroundWork {
                override fun uploadReports(delayMillis: Long) = Unit

                override fun scheduleListSync(allowMobileData: Boolean) = Unit

                override fun syncListNow() = Unit
            },
        )
    val contactLookup =
        object : ContactLookup {
            override fun canRead(): Boolean = canReadContacts

            override suspend fun displayName(number: PhoneNumber): String? = contacts[number]
        }
    val engine =
        ScreeningEngine(
            DeviceSources({ settings.preferences.first() }, contactLookup, { "US" }, { false }),
            ListSources(
                rules = { rules.enabledCallRules().orFail() },
                verifiedBusinesses = { false },
                spamList = { spam.find(it).orFail() },
                reputation = { null },
                network = NoNetworkLookup,
            ),
            normalizer,
            dispatchers,
            TimeSource.Monotonic,
            ScreeningConfig(),
        )
    val validator = RuleValidator(normalizer, TimeLimitedRegex(ScreeningConfig.DEFAULT_REGEX_BUDGET_MILLIS))

    fun close() {
        db.close()
    }
}

/** Waits (in real time) for the first value of [flow] matching [predicate]. */
suspend fun <T> awaitValue(
    flow: Flow<T>,
    predicate: (T) -> Boolean,
): T = withContext(Dispatchers.Default) { withTimeout(5_000) { flow.first(predicate) } }

/** The success value, or a test failure. */
fun <T> AppResult<T>.orFail(): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> error("Expected success, got $error")
    }
