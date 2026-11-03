package io.github.jeshiomurmu.opencaller.server.lists

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.config.ListConfig
import io.github.jeshiomurmu.opencaller.server.db.ListVersions
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** One country's new list version. */
@Serializable
data class CountryBuild(
    val countryIso: String,
    val version: Long,
    val entries: Int,
    val changed: Int,
    val removed: Int,
    val extendedEntries: Int = 0,
)

/** What one list build did: new versions, and how many countries had no changes. */
@Serializable
data class ListBuildResult(
    val built: List<CountryBuild>,
    val unchanged: Int,
)

/**
 * Builds the per-country spam lists (docs/architecture.md §4.8, §13), nightly and on demand. A number is listed
 * when its spam score is at least [ListConfig.minScore]; its country is libphonenumber's region for it. A country's
 * standard list keeps its [ListConfig.standardMaxEntries] most-reported numbers (then highest score, then lowest
 * number); the rest form its extended list (F-PR-03). For each country whose lists changed it writes version N+1 as
 * a full file, a delta from N if N exists, and an extended file if the extended list isn't empty, uploads them to
 * the object store and records them in `list_versions`. Unchanged countries get no new version.
 */
class ListBuilder(
    private val db: ServerDatabase,
    private val store: ObjectStore,
    private val config: ListConfig,
    private val clock: Clock,
) {
    private val util = PhoneNumberUtil.getInstance()

    private data class Published(
        val version: Long,
        val objectKey: String,
    )

    /** Runs one build (the caller holds the leader lock). */
    suspend fun build(): ListBuildResult {
        val now = clock.instant()
        val current = db.query { listedByCountry() }
        val latest = db.query { latestFullVersions() }
        val built = mutableListOf<CountryBuild>()
        var unchanged = 0
        (current.keys + latest.keys).sorted().forEach { iso ->
            val result = buildCountry(iso, current[iso].orEmpty(), latest[iso], now)
            if (result == null) unchanged++ else built += result
        }
        return ListBuildResult(built, unchanged)
    }

    private suspend fun buildCountry(
        iso: String,
        entries: List<ListEntry>,
        previous: Published?,
        now: Instant,
    ): CountryBuild? {
        val (standard, extended) = split(entries)
        val base = previous?.let { readPrevious(iso, it.objectKey, it.version) }
        val baseExtended = previous?.let { readPrevious(iso, objectKey(iso, it.version, ListKind.EXTENDED), it.version, quiet = true) }
        if (base != null && base.entries == standard && baseExtended?.entries.orEmpty() == extended) return null
        val version = (previous?.version ?: 0) + 1
        val uploads = mutableListOf(ListKind.FULL to ListFormat.encode(ListFile(ListKind.FULL, iso, version, 0, now.epochSecond, standard)))
        var changed = standard.size
        var removed = 0
        if (base != null) {
            val before = base.entries.associateBy { it.number }
            val upserts = standard.filter { before[it.number] != it }
            val kept = standard.map { it.number }.toSet()
            val removals = base.entries.map { it.number }.filter { it !in kept }
            changed = upserts.size
            removed = removals.size
            uploads +=
                ListKind.DELTA to
                ListFormat.encode(ListFile(ListKind.DELTA, iso, version, base.version, now.epochSecond, upserts, removals))
        }
        if (extended.isNotEmpty()) {
            uploads += ListKind.EXTENDED to ListFormat.encode(ListFile(ListKind.EXTENDED, iso, version, 0, now.epochSecond, extended))
        }
        publish(iso, version, uploads, now)
        return CountryBuild(iso, version, standard.size, changed, removed, extended.size)
    }

    // The standard list keeps the most-reported numbers up to the cap; both parts are returned sorted by number.
    private fun split(entries: List<ListEntry>): Pair<List<ListEntry>, List<ListEntry>> {
        if (entries.size <= config.standardMaxEntries) return entries.sortedBy { it.number } to emptyList()
        val ranked =
            entries.sortedWith(
                compareByDescending<ListEntry> { it.reportCount }.thenByDescending { it.scorePercent }.thenBy { it.number },
            )
        return ranked.take(config.standardMaxEntries).sortedBy { it.number } to
            ranked.drop(config.standardMaxEntries).sortedBy { it.number }
    }

    private suspend fun publish(
        iso: String,
        version: Long,
        uploads: List<Pair<ListKind, ByteArray>>,
        now: Instant,
    ) {
        val rows =
            uploads.map { (kind, bytes) ->
                val key = objectKey(iso, version, kind)
                store.put(key, bytes, CONTENT_TYPE)
                Triple(kind, key, ListFormat.sha256Hex(bytes))
            }
        db.query {
            ListVersions.batchInsert(rows, shouldReturnGeneratedValues = false) { (kind, key, sha) ->
                this[ListVersions.countryIso] = iso
                this[ListVersions.version] = version
                this[ListVersions.kind] = kind.sqlValue
                this[ListVersions.objectKey] = key
                this[ListVersions.sha256] = sha
                this[ListVersions.createdAt] = now.atOffset(ZoneOffset.UTC)
            }
        }
    }

    // A missing or unreadable previous full file means no delta this time; clients then download the full list.
    // A version without an extended file had an empty extended list ([quiet]: no warning).
    private suspend fun readPrevious(
        iso: String,
        key: String,
        version: Long,
        quiet: Boolean = false,
    ): ListFile? {
        val bytes = store.get(key)
        if (bytes == null) {
            if (!quiet) log.warn("List {} v{} is missing from the object store; publishing a full list only", iso, version)
            return null
        }
        return try {
            ListFormat.decode(bytes)
        } catch (e: ListFormatException) {
            log.warn("List {} v{} ({}) is unreadable; publishing it again in full", iso, version, key, e)
            null
        }
    }

    private fun JdbcTransaction.listedByCountry(): Map<String, List<ListEntry>> {
        val listed = mutableMapOf<String, MutableList<ListEntry>>()
        NumberReputation
            .select(NumberReputation.e164, NumberReputation.spamScore, NumberReputation.category, NumberReputation.reportCount)
            .where { (NumberReputation.spamScore greaterEq config.minScore.toFloat()) and (NumberReputation.reportCount greater 0) }
            .fetchSize(FETCH_SIZE)
            .forEach { row ->
                val e164 = row[NumberReputation.e164]
                val region = util.getRegionCodeForNumber(util.parse(e164, null))
                // Non-geographic numbers (region "001", e.g. +800) and unknown regions have no country list.
                if (region != null && region.length == 2) {
                    listed.getOrPut(region) { mutableListOf() } +=
                        ListEntry.of(
                            e164,
                            row[NumberReputation.spamScore].toDouble(),
                            row[NumberReputation.category],
                            row[NumberReputation.reportCount].toLong(),
                        )
                }
            }
        return listed
    }

    private fun JdbcTransaction.latestFullVersions(): Map<String, Published> =
        exec(
            "SELECT DISTINCT ON (country_iso) country_iso, version, object_key FROM list_versions " +
                "WHERE kind = 'full' ORDER BY country_iso, version DESC",
        ) { rs ->
            buildMap { while (rs.next()) put(rs.getString("country_iso"), Published(rs.getLong("version"), rs.getString("object_key"))) }
        }.orEmpty()

    /** Object keys and content type. */
    companion object {
        /** Content type of list files. */
        const val CONTENT_TYPE = "application/octet-stream"
        private const val FETCH_SIZE = 10_000
        private val log = LoggerFactory.getLogger(ListBuilder::class.java)

        /**
         * Object key of a list file, e.g. `lists/BD/3/full.ocsl` (the delta in the same folder turns v2 into v3,
         * `extended.ocsl` is the extended list).
         */
        fun objectKey(
            iso: String,
            version: Long,
            kind: ListKind,
        ) = "lists/$iso/$version/${kind.sqlValue}.ocsl"
    }
}
