package io.github.jeshiomurmu.opencaller.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import mockwebserver3.MockResponse
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/** An unencrypted in-memory database (SQLCipher's native library is Android-only). */
fun inMemoryDatabase(): OpenCallerDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenCallerDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** A Preferences DataStore in a fresh temporary directory. */
fun tempPreferences(name: String) =
    PreferenceDataStoreFactory.create { File(createTempDirectory("datastore").toFile(), "$name.preferences_pb") }

/** Real dispatchers, for tests that talk to a local HTTP server. */
object RealDispatchers : io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider {
    override val main: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Default
    override val io: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.IO
    override val default: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Default
}

/** Records background work instead of scheduling it. */
class RecordingBackgroundWork : BackgroundWork {
    val uploads = mutableListOf<Long>()
    val listSyncs = mutableListOf<Boolean>()
    var syncNowCount = 0

    override fun uploadReports(delayMillis: Long) {
        uploads += delayMillis
    }

    override fun scheduleListSync(allowMobileData: Boolean) {
        listSyncs += allowMobileData
    }

    override fun syncListNow() {
        syncNowCount++
    }
}

/** Tokens in memory. */
class MemoryTokens(
    var tokens: AuthTokens? = null,
) : TokenStore {
    override suspend fun read(): AuthTokens? = tokens

    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

/** A JSON response. */
fun jsonResponse(
    code: Int,
    body: String,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

/** A binary response. */
fun bytesResponse(bytes: ByteArray): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/octet-stream")
        .body(Buffer().write(bytes))
        .build()

/** One entry of a test list file. */
data class TestEntry(
    val e164: String,
    val percent: Int,
    val category: String?,
    val reports: Long,
)

/**
 * Writes list files in the format of docs/architecture.md §13, like the server's `ListFormat.encode` (its bytes are
 * checked against the server's own vectors in core:network's SpamListFormatTest).
 */
object TestListFiles {
    fun full(
        country: String,
        version: Long,
        entries: List<TestEntry>,
    ): ByteArray = encode(Header(1, country, version, 0), entries, emptyList())

    fun delta(
        country: String,
        version: Long,
        base: Long,
        upserts: List<TestEntry>,
        removals: List<String>,
    ): ByteArray = encode(Header(2, country, version, base), upserts, removals)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private class Header(
        val kind: Int,
        val country: String,
        val version: Long,
        val base: Long,
    )

    private fun encode(
        header: Header,
        entries: List<TestEntry>,
        removals: List<String>,
    ): ByteArray {
        val sorted = entries.sortedBy { it.e164.removePrefix("+").toLong() }
        val categories = sorted.mapNotNull { it.category }.distinct().sorted()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).run {
            write("OCSL".toByteArray())
            writeByte(1)
            writeByte(header.kind)
            write(header.country.toByteArray())
            writeLong(header.version)
            writeLong(header.base)
            writeLong(1_792_152_000L)
            writeByte(categories.size)
            categories.forEach {
                writeByte(it.length)
                write(it.toByteArray())
            }
            writeInt(sorted.size)
            writeInt(removals.size)
            sorted.forEach {
                writeLong(it.e164.removePrefix("+").toLong())
                writeByte(it.percent)
                writeByte(it.category?.let(categories::indexOf) ?: 0xFF)
                writeInt(it.reports.toInt())
            }
            removals.map { it.removePrefix("+").toLong() }.sorted().forEach(::writeLong)
            flush()
        }
        val body = out.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
}
