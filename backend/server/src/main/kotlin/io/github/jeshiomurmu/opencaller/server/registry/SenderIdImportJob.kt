package io.github.jeshiomurmu.opencaller.server.registry

import io.github.jeshiomurmu.opencaller.server.db.ImportRuns
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.sync.RegistryRules
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** One recorded import run, as the admin web lists them. */
data class ImportRun(
    val source: String,
    val countryIso: String?,
    val startedAt: OffsetDateTime,
    val status: String,
    val upserted: Int,
    val removed: Int,
    val detail: String?,
)

/** What one scheduled import run did. */
@Serializable
data class ImportJobResult(
    val ok: Int,
    val failed: Int,
    val unavailable: Int,
)

/** The `import_runs` table: every importer run, successful or not. */
class ImportRunLog(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Records a run of [importer] that started at [startedAt]. */
    suspend fun record(
        importer: Importer<*>,
        startedAt: OffsetDateTime,
        status: String,
        result: ImportResult? = null,
        detail: String? = null,
    ) {
        db.query {
            ImportRuns.insert {
                it[sourceName] = importer.source
                it[countryIso] = importer.countryIso
                it[ImportRuns.startedAt] = startedAt
                it[finishedAt] = OffsetDateTime.now(clock)
                it[ImportRuns.status] = status
                it[upserted] = result?.upserted ?: 0
                it[removed] = result?.removed ?: 0
                it[ImportRuns.detail] = detail?.take(MAX_DETAIL)
            }
        }
    }

    /** The latest [limit] runs, newest first. */
    suspend fun recent(limit: Int = RECENT): List<ImportRun> =
        db.query {
            ImportRuns
                .selectAll()
                .orderBy(ImportRuns.startedAt to SortOrder.DESC)
                .limit(limit)
                .map {
                    ImportRun(
                        source = it[ImportRuns.sourceName],
                        countryIso = it[ImportRuns.countryIso],
                        startedAt = it[ImportRuns.startedAt],
                        status = it[ImportRuns.status],
                        upserted = it[ImportRuns.upserted],
                        removed = it[ImportRuns.removed],
                        detail = it[ImportRuns.detail],
                    )
                }
        }

    private companion object {
        const val MAX_DETAIL = 2_000
        const val RECENT = 50
    }
}

/**
 * Runs every importer once and records each run: the CSV extracts under the import folder, then the placeholder
 * registries (recorded as `unavailable`, so the gap stays visible). Daily, under its own advisory lock.
 */
class SenderIdImportJob(
    private val importers: () -> List<Importer<*>>,
    private val log: ImportRunLog,
    private val clock: Clock,
) {
    /** Runs once (the caller holds the leader lock). */
    suspend fun run(): ImportJobResult {
        var ok = 0
        var failed = 0
        var unavailable = 0
        importers().forEach { importer ->
            when (runRecorded(importer, log, clock)) {
                STATUS_OK -> ok++
                STATUS_UNAVAILABLE -> unavailable++
                else -> failed++
            }
        }
        return ImportJobResult(ok, failed, unavailable)
    }

    /** Job name and statuses. */
    companion object {
        /** Name of the job (`POST /admin/jobs/sender-ids`). */
        const val JOB_NAME = "sender-ids"

        /** Status of a run that wrote its batch. */
        const val STATUS_OK = "ok"

        /** Status of a run that failed (unreadable file, store error). */
        const val STATUS_FAILED = "failed"

        /** Status of a registry without documented access. */
        const val STATUS_UNAVAILABLE = "unavailable"

        private val logger = LoggerFactory.getLogger(SenderIdImportJob::class.java)

        /** Runs [importer] once, records the run in [log] and returns its status. */
        suspend fun runRecorded(
            importer: Importer<*>,
            log: ImportRunLog,
            clock: Clock,
        ): String {
            val started = OffsetDateTime.now(clock)
            return try {
                val result = importer.runOnce()
                val skipped = (importer as? CsvSenderIdImporter)?.let { "rows skipped: ${result.rejected}" }
                log.record(importer, started, STATUS_OK, result, skipped)
                STATUS_OK
            } catch (e: RegistryUnavailableException) {
                log.record(importer, started, STATUS_UNAVAILABLE, detail = e.message)
                STATUS_UNAVAILABLE
            } catch (e: IOException) {
                logger.warn("Sender-ID import {} failed", importer.source, e)
                log.record(importer, started, STATUS_FAILED, detail = "could not read the extract: ${e.message}")
                STATUS_FAILED
            }
        }
    }
}

/**
 * CSV importers for the extracts in [dir]: one per `<source>/<ISO>.csv` (e.g. `csv-in-dlt/IN.csv`), each the
 * source's complete list for that country. Files larger than [MAX_FILE_BYTES] and badly named ones are skipped
 * (logged). An unset or missing folder gives no importers.
 */
fun csvImportersIn(
    dir: String?,
    store: SenderIdStore,
): List<Importer<*>> {
    val root = dir?.let(Path::of)?.takeIf { it.isDirectory() } ?: return emptyList()
    return try {
        root
            .listDirectoryEntries()
            .filter { it.isDirectory() && RegistryRules.SOURCE.matches(it.name) }
            .sortedBy { it.name }
            .flatMap { sourceDir ->
                sourceDir.listDirectoryEntries("*.csv").sortedBy { it.name }.mapNotNull { importerFor(sourceDir.name, it, store) }
            }
    } catch (e: IOException) {
        LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.registry").warn("Could not list the import folder", e)
        emptyList()
    }
}

private fun importerFor(
    source: String,
    file: Path,
    store: SenderIdStore,
): Importer<*>? {
    val iso = file.name.removeSuffix(".csv")
    if (!RegistryRules.ISO.matches(iso) || !file.isRegularFile() || Files.size(file) > MAX_FILE_BYTES) {
        LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.registry").warn("Skipping import file {}/{}", source, file.name)
        return null
    }
    return CsvSenderIdImporter(source, iso, store, complete = true) {
        if (file.fileSize() > MAX_FILE_BYTES) throw IOException("${file.name} grew past $MAX_FILE_BYTES bytes")
        file.readText(Charsets.UTF_8)
    }
}

/** Largest CSV extract read from the import folder or uploaded in the admin web. */
const val MAX_FILE_BYTES = 20_000_000L
