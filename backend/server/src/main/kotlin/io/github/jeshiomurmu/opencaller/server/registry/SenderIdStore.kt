package io.github.jeshiomurmu.opencaller.server.registry

import io.github.jeshiomurmu.opencaller.server.db.SenderIds
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Writes imported sender IDs into `sender_ids` so apps sync only real changes: a new or changed row (or one
 * another source had) gets the import's time as `updated_at` and [source] as its source; an unchanged row is left
 * alone. A [ImportBatch.complete] extract also removes this source's rows (for that country) it no longer lists,
 * keeping them with `deleted_at` so apps remove them too.
 */
class SenderIdStore(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Applies [batch] of [source] for [countryIso]. */
    suspend fun apply(
        source: String,
        countryIso: String,
        batch: ImportBatch,
    ): ImportResult =
        db.query {
            val now = OffsetDateTime.now(clock)
            val records = batch.records.filter { it.countryIso == countryIso }.associateBy { it.header }
            val existing = current(countryIso, records.keys)
            val changed = records.values.filter { record -> existing[record.header] != Snapshot(record, source) }
            changed.chunked(CHUNK).forEach { chunk ->
                SenderIds.batchUpsert(chunk, shouldReturnGeneratedValues = false) { record ->
                    this[SenderIds.countryIso] = record.countryIso
                    this[SenderIds.header] = record.header
                    this[SenderIds.brandName] = record.brandName
                    this[SenderIds.category] = record.category
                    this[SenderIds.sourceName] = source
                    this[SenderIds.updatedAt] = now
                    this[SenderIds.deletedAt] = null
                }
            }
            val removed = if (batch.complete) removeMissing(source, countryIso, records.keys, now) else 0
            val inserted = changed.count { it.header !in existing }
            ImportResult(
                inserted = inserted,
                updated = changed.size - inserted,
                removed = removed,
                unchanged = records.size - changed.size,
                rejected = batch.rejected.size + (batch.records.size - records.size),
            )
        }

    private data class Snapshot(
        val brandName: String,
        val category: String,
        val source: String,
    ) {
        constructor(record: SenderIdRecord, source: String) : this(record.brandName, record.category, source)
    }

    // The live rows among [headers] (a deleted row counts as missing, so importing it again restores it).
    private fun JdbcTransaction.current(
        countryIso: String,
        headers: Set<String>,
    ): Map<String, Snapshot> =
        headers
            .chunked(CHUNK)
            .flatMap { chunk ->
                SenderIds
                    .selectAll()
                    .where { (SenderIds.countryIso eq countryIso) and (SenderIds.header inList chunk) and SenderIds.deletedAt.isNull() }
                    .map { it[SenderIds.header] to Snapshot(it[SenderIds.brandName], it[SenderIds.category], it[SenderIds.sourceName]) }
            }.toMap()

    private fun JdbcTransaction.removeMissing(
        source: String,
        countryIso: String,
        keep: Set<String>,
        now: OffsetDateTime,
    ): Int {
        val stale =
            SenderIds
                .select(SenderIds.header)
                .where { (SenderIds.countryIso eq countryIso) and (SenderIds.sourceName eq source) and SenderIds.deletedAt.isNull() }
                .map { it[SenderIds.header] }
                .filter { it !in keep }
        stale.chunked(CHUNK).forEach { chunk ->
            SenderIds.update({ (SenderIds.countryIso eq countryIso) and (SenderIds.header inList chunk) }) {
                it[updatedAt] = now
                it[deletedAt] = now
            }
        }
        return stale.size
    }

    private companion object {
        const val CHUNK = 1_000
    }
}
