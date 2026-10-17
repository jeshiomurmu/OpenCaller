package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncOutcome
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncRepository
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncState
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncStateStore
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spam-list updates against a local server: deltas, full lists, checksums (TC-SYNC-001, TC-SYNC-002). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class SpamListSyncTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val clock = FakeClock()
    private val network = OpenCallerNetwork(ServerConfig(server.url("/").toString()), MemoryTokens(), clock)
    private val stateStore = SpamListSyncStateStore(tempPreferences("sync"))
    private var country: String? = "BD"
    private val repository =
        SpamListSyncRepository(
            api = network.api,
            access = DatabaseAccess { db },
            dispatchers = RealDispatchers,
            clock = clock,
            stateStore = stateStore,
            country = { country },
        )

    // Files the fake server publishes, by URL path, and the paths requested.
    private val files = mutableMapOf<String, ByteArray>()
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var manifest: String? = null

    private val v1 =
        listOf(
            TestEntry("+8801711111111", 91, "scam", 214),
            TestEntry("+8801722222222", 62, null, 7),
            TestEntry("+8801733333333", 45, "loan", 3),
        )
    private val v2 =
        listOf(
            TestEntry("+8801711111111", 93, "scam", 230),
            TestEntry("+8801733333333", 45, "loan", 3),
            TestEntry("+8801744444444", 88, "robocall", 51),
        )
    private val v3 = v2.filter { it.e164 != "+8801733333333" } + TestEntry("+8801755555555", 70, "telemarketing", 12)

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requested += path
                    return when {
                        path == "/v1/lists/BD/manifest" -> {
                            manifest?.let { jsonResponse(200, it) }
                                ?: jsonResponse(404, """{"code":"list_not_found"}""")
                        }

                        files.containsKey(path) -> {
                            bytesResponse(files.getValue(path))
                        }

                        else -> {
                            jsonResponse(404, """{"code":"not_found"}""")
                        }
                    }
                }
            }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun publish(
        version: Long,
        full: ByteArray,
        deltas: Map<Long, ByteArray> = emptyMap(),
        corruptSha: Boolean = false,
    ) {
        files["/v1/lists/BD/files/$version/full"] = full
        deltas.forEach { (v, bytes) -> files["/v1/lists/BD/files/$v/delta"] = bytes }

        fun ref(
            v: Long,
            kind: String,
            bytes: ByteArray,
        ): String {
            val sha = if (corruptSha) "0".repeat(64) else TestListFiles.sha256(bytes)
            val base = if (kind == "delta") v - 1 else null
            return """{"version":$v,"baseVersion":$base,"url":"/v1/lists/BD/files/$v/$kind",""" +
                """"sha256":"$sha","createdAt":"2026-10-17T03:00:00Z"}"""
        }
        val deltaRefs = deltas.entries.sortedByDescending { it.key }.joinToString(",") { (v, bytes) -> ref(v, "delta", bytes) }
        manifest = """{"countryIso":"BD","format":1,"version":$version,"full":${ref(version, "full", full)},"deltas":[$deltaRefs]}"""
    }

    private suspend fun table(): List<SpamListEntry> =
        listOf("+8801711111111", "+8801722222222", "+8801733333333", "+8801744444444", "+8801755555555")
            .mapNotNull { db.spamListDao().find(it) }

    @Test
    fun theFirstUpdateDownloadsTheFullList() =
        runBlocking {
            publish(1, TestListFiles.full("BD", 1, v1))
            val outcome = repository.sync()
            assertEquals(AppResult.Success(SpamListSyncOutcome.Updated("BD", 1, files = 1, full = true)), outcome)
            assertEquals(3, db.spamListDao().count())
            assertEquals(SpamListEntry("+8801711111111", 0.91f, "scam", 214, 1), db.spamListDao().find("+8801711111111"))
            assertEquals(SpamListEntry("+8801722222222", 0.62f, "", 7, 1), db.spamListDao().find("+8801722222222"))
            assertEquals(SpamListSyncState("BD", 1, clock.nowMillis()), stateStore.state.first())
            // Nothing new: no download.
            val before = server.requestCount
            assertEquals(AppResult.Success(SpamListSyncOutcome.UpToDate), repository.sync())
            assertEquals(before + 1, server.requestCount)
        }

    // TC-SYNC-001: v1 + delta equals the full v2, row for row.
    @Test
    fun deltasEqualTheFullListRowForRow() =
        runBlocking {
            publish(1, TestListFiles.full("BD", 1, v1))
            repository.sync()
            val delta2 = TestListFiles.delta("BD", 2, 1, upserts = v2.filter { it !in v1 }, removals = listOf("+8801722222222"))
            val delta3 =
                TestListFiles.delta(
                    "BD",
                    3,
                    2,
                    upserts = v3.filter { it !in v2 },
                    removals = listOf("+8801733333333"),
                )
            publish(3, TestListFiles.full("BD", 3, v3), deltas = mapOf(2L to delta2, 3L to delta3))
            requested.clear()
            val outcome = repository.sync()
            assertEquals(listOf("/v1/lists/BD/manifest", "/v1/lists/BD/files/2/delta", "/v1/lists/BD/files/3/delta"), requested.toList())
            assertEquals(AppResult.Success(SpamListSyncOutcome.Updated("BD", 3, files = 2, full = false)), outcome)
            val viaDeltas = table()
            // The same version applied as a full list.
            db.spamListDao().deleteAll()
            stateStore.save(SpamListSyncState())
            repository.sync()
            assertEquals(table(), viaDeltas)
            assertEquals(3, viaDeltas.size)
            assertTrue(viaDeltas.all { it.listVersion == 3L })
        }

    // TC-SYNC-002: a wrong checksum is rejected and the old list kept.
    @Test
    fun aCorruptDownloadKeepsTheOldList() =
        runBlocking {
            publish(1, TestListFiles.full("BD", 1, v1))
            repository.sync()
            val before = table()
            // The manifest lists another file's checksum.
            publish(2, TestListFiles.full("BD", 2, v2), corruptSha = true)
            stateStore.save(SpamListSyncState("IN", 9, 0))
            val wrongSha = repository.sync()
            assertTrue(wrongSha is AppResult.Failure && wrongSha.error is AppError.Validation)
            assertEquals(before, table())
            // A file whose bytes were altered (its trailer no longer matches).
            val good = TestListFiles.full("BD", 2, v2)
            val altered = good.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
            publish(2, good)
            files["/v1/lists/BD/files/2/full"] = altered
            manifest = manifest!!.replace(TestListFiles.sha256(good), TestListFiles.sha256(altered))
            val badTrailer = repository.sync()
            assertTrue(badTrailer is AppResult.Failure && badTrailer.error is AppError.Validation)
            assertEquals(before, table())
            assertEquals(SpamListSyncState("IN", 9, 0), stateStore.state.first())
        }

    @Test
    fun aFileForAnotherVersionIsRejected() =
        runBlocking {
            publish(2, TestListFiles.full("BD", 1, v1))
            val result = repository.sync()
            assertTrue(result is AppResult.Failure && result.error is AppError.Validation)
            assertEquals(0, db.spamListDao().count())
        }

    @Test
    fun unpublishedCountriesAndUnknownCountriesAreNoOps() =
        runBlocking {
            assertEquals(AppResult.Success(SpamListSyncOutcome.NothingPublished), repository.sync())
            country = null
            assertEquals(AppResult.Success(SpamListSyncOutcome.NoCountry), repository.sync())
        }

    @Test
    fun offlineKeepsTheOldList() =
        runBlocking {
            publish(1, TestListFiles.full("BD", 1, v1))
            repository.sync()
            server.close()
            assertEquals(AppResult.Failure(AppError.Network), repository.sync())
            assertEquals(3, db.spamListDao().count())
        }
}
